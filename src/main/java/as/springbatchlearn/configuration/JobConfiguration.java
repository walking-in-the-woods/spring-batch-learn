package as.springbatchlearn.configuration;

/*
    Within Spring Batch there are two main ways to distribute the work load across multiple JVMs:
    -   Remote partitioning, which is covered in previous commit, addresses the use cases that are IO-bound.
        In that slaves are responsible for the input and output, what may be useful in many use cases.
        But in use cases where the processing itself is the bottleneck and you can not scale the input
    -   Remote chunking can be a great help. With remote chunking, the input of the chunk-based step is read
        by the master. Then the items themselves are sent over persistence middleware to slaves for processing.
        The result can be either returned to the master for writing or written by the slaves themselves.
        The key difference here between remote partitioning and remote chunking is that in remote partitioning
        only a description of the data is sent over the wire, the slaves are managed to reading the data themselves.
        In remote chunking, the master does the reading and sends the data over the wire. So a few of a million
        items to process with remote partitioning, you can configure how many messages go over the wire by
        configuring how many partitions there are. With remote chunking, if you have a million items to process,
        all million of those items are going over the wire.
*/

/*
    Running instructions:

    java -jar Dspring.profiles.active=slave target/spring-batch-learn-0.0.1-SNAPSHOT.jar    // running a slave
    java -jar Dspring.profiles.active=master target/spring-batch-learn-0.0.1-SNAPSHOT.jar    // running a master

    Adding more slaves increases the all our processing.

    One additional difference between the remote partitioning and remote chunking should be noted.
    With partitioning there are multiple step executions, one per partition, and one additional one for the master.
    However with remote chunking, there is just one step execution. Because of this unlike remote partitioning
    where the state is managed at the slave, state is managed within a master for remote chunking.
    This means a persistent middleware is required for communication so that items in fly are not lost upon en error.

*/

import as.springbatchlearn.domain.Customer;
import as.springbatchlearn.domain.CustomerRowMapper;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.step.tasklet.TaskletStep;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.database.BeanPropertyItemSqlParameterSourceProvider;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.support.PostgresPagingQueryProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import javax.sql.DataSource;
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

    @Autowired
    public JobExplorer jobExplorer;

    @Bean
    public JdbcPagingItemReader<Customer> pagingItemReader() {
        JdbcPagingItemReader<Customer> reader = new JdbcPagingItemReader<>();

        reader.setDataSource(this.dataSource);
        reader.setFetchSize(1000);
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
    ItemProcessor<Customer, Customer> upperCaseItemProcessor() {
        return item -> new Customer(item.getId(),
                item.getFirstName(),
                item.getLastName(),
                item.getBirthdate());
    }

    @Bean
    public JdbcBatchItemWriter<Customer> customerItemWriter() {
        JdbcBatchItemWriter<Customer> itemWriter = new JdbcBatchItemWriter<>();

        itemWriter.setDataSource(this.dataSource);
        itemWriter.setSql("INSERT INTO NEW_CUSTOMER VALUES (:id, :firstName, :lastName, :birthdate)");
        itemWriter.setItemSqlParameterSourceProvider(new BeanPropertyItemSqlParameterSourceProvider<>());
        itemWriter.afterPropertiesSet();

        return itemWriter;
    }

    @Bean
    TaskletStep step1() {
        return stepBuilderFactory.get("step1")
                .<Customer, Customer>chunk(1000)
                .reader(pagingItemReader())
                .processor(upperCaseItemProcessor())
                .writer(customerItemWriter())
                .build();
    }

    @Bean
    @Profile("master")  // This is to prevent the job from kicking off in the slave JVMs.
    public Job job() throws Exception {
        return jobBuilderFactory.get("job")
                .start(step1())
                .build();
    }
}
