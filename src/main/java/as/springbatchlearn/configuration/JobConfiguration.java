package as.springbatchlearn.configuration;

import as.springbatchlearn.domain.Customer;
import as.springbatchlearn.domain.CustomerLineAggregator;
import as.springbatchlearn.domain.CustomerRowMapper;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.support.PostgresPagingQueryProvider;
import org.springframework.batch.item.file.FlatFileItemWriter;
import org.springframework.batch.item.support.ClassifierCompositeItemWriter;
import org.springframework.batch.item.xml.StaxEventItemWriter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.FileSystemResource;
import org.springframework.oxm.xstream.XStreamMarshaller;

import javax.sql.DataSource;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class JobConfiguration {

    @Autowired
    private JobBuilderFactory jobBuilderFactory;

    @Autowired
    private StepBuilderFactory stepBuilderFactory;

    @Autowired
    public DataSource dataSource;

    @Bean
    public JdbcPagingItemReader<Customer> pagingItemReader() {
        JdbcPagingItemReader<Customer> reader = new JdbcPagingItemReader<>();

        reader.setDataSource(this.dataSource);
        reader.setFetchSize(10);
        reader.setRowMapper(new CustomerRowMapper());

        PostgresPagingQueryProvider queryProvider = new PostgresPagingQueryProvider();
        queryProvider.setSelectClause("id, firstName, lastName, birthdate");
        queryProvider.setFromClause("from customer");

        Map<String, Order> sortKeys = new HashMap<>(1);
        sortKeys.put("id", Order.ASCENDING);
        queryProvider.setSortKeys(sortKeys);
        reader.setQueryProvider(queryProvider);

        return reader;
    }

    @Bean
    public FlatFileItemWriter<Customer> jsonItemWriter() throws Exception {
        FlatFileItemWriter<Customer> itemWriter = new FlatFileItemWriter<>();

        itemWriter.setLineAggregator(new CustomerLineAggregator());
        String customerOutputPath = File.createTempFile("customerOutput", ".out").getAbsolutePath();
        System.out.println(">> Output Path: " + customerOutputPath);
        itemWriter.setResource(new FileSystemResource(customerOutputPath));
        itemWriter.afterPropertiesSet();

        return itemWriter;
    }

    @Bean
    public StaxEventItemWriter<Customer> xmlItemWriter() throws Exception {
        XStreamMarshaller marshaller = new XStreamMarshaller();

        Map<String, Class> aliases = new HashMap<>();
        aliases.put("customer", Customer.class);

        marshaller.setAliases(aliases);

        StaxEventItemWriter<Customer> itemWriter = new StaxEventItemWriter<>();

        itemWriter.setRootTagName("customers");
        itemWriter.setMarshaller(marshaller);   // generates individual blocks item by item
        String customerOutputPath = File.createTempFile("customerOutput", ".xml").getAbsolutePath();
        System.out.println(">> Output Path: " + customerOutputPath);
        itemWriter.setResource(new FileSystemResource(customerOutputPath));

        itemWriter.afterPropertiesSet();

        return itemWriter;
    }

    // comment this bean if you use ClassifierCompositeItemWriter
    // CompositeItemWriter implements item stream
//    @Bean
//    public CompositeItemWriter<Customer> itemWriter() throws Exception {
//        List<ItemWriter<? super Customer>> writers = new ArrayList<>(2);
//
//        writers.add(xmlItemWriter());
//        writers.add(jsonItemWriter());
//
//        CompositeItemWriter<Customer> itemWriter = new CompositeItemWriter<>();
//
//        itemWriter.setDelegates(writers);
//        itemWriter.afterPropertiesSet();
//
//        return itemWriter;
//    }

    // comment this bean if you use CompositeItemWriter
    // CompositeItemWriter does not implements item stream, so we need to add streams to the step
    @Bean
    public ClassifierCompositeItemWriter<Customer> itemWriter() throws Exception {
        ClassifierCompositeItemWriter<Customer> itemWriter = new ClassifierCompositeItemWriter<>();

        // even goes to xml, odd goes to json
        itemWriter.setClassifier(new CustomerClassifier(xmlItemWriter(), jsonItemWriter()));

        return itemWriter;
    }

    @Bean
    public Step step1() throws Exception {
        return stepBuilderFactory.get("step1")
                .<Customer, Customer>chunk(10)
                .reader(pagingItemReader())
                .writer(itemWriter())
                .stream(xmlItemWriter())  // comment this line if you use CompositeItemWriter
                .stream(jsonItemWriter()) // comment this line if you use CompositeItemWriter
                .allowStartIfComplete(true)
                .build();
    }

    @Bean
    public Job job() throws Exception {
        return jobBuilderFactory.get("job")
                .start(step1())
                .build();
    }
}
