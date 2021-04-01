package as.springbatchlearn.configuration;

/*
    Remote partitioning allows us to use multiple JVMs (in the cloud for example)
    The key to this is the MessageChannelPartitionHandler. This implementation of the PartitionHandler interface
    uses messages to communicate with slaves to execute the slave steps.
    In our example we'll use RabbitMQ to communicate between the master and the slaves.

 */

import as.springbatchlearn.domain.ColumnRangePartitioner;
import as.springbatchlearn.domain.Customer;
import as.springbatchlearn.domain.CustomerRowMapper;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.explore.JobExplorer;
import org.springframework.batch.core.partition.PartitionHandler;
import org.springframework.batch.integration.partition.BeanFactoryStepLocator;
import org.springframework.batch.integration.partition.MessageChannelPartitionHandler;
import org.springframework.batch.integration.partition.StepExecutionRequestHandler;
import org.springframework.batch.item.database.BeanPropertyItemSqlParameterSourceProvider;
import org.springframework.batch.item.database.JdbcBatchItemWriter;
import org.springframework.batch.item.database.JdbcPagingItemReader;
import org.springframework.batch.item.database.Order;
import org.springframework.batch.item.database.support.PostgresPagingQueryProvider;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.core.MessagingTemplate;
import org.springframework.integration.scheduling.PollerMetadata;
import org.springframework.scheduling.support.PeriodicTrigger;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class JobConfiguration implements ApplicationContextAware {

    @Autowired
    private JobBuilderFactory jobBuilderFactory;

    @Autowired
    private StepBuilderFactory stepBuilderFactory;

    @Autowired
    public DataSource dataSource;

    @Autowired
    public JobExplorer jobExplorer;

    private ApplicationContext applicationContext;

    private static final int GRID_SIZE = 4;

    /*  The only difference is here. Here we're using a MessageChannelPartitionHandler
        instead of TaskExecutorPartitionHandler.

        There are two ways for this partition handler to know that all the slave work is done:
        1.  Listen to messages to be returned. The configuration for that is much more complex.
        2.  Poll the JobRepository. Since all the slave steps record is step execution for each partition,
            we can poll the job repository and see if all of those slaves step executions are in the terminal state.
            (completed, failed, etc). If they are, we know that the slave work is done and we can continue on.
    */
    @Bean
    public PartitionHandler partitionHandler(MessagingTemplate messagingTemplate) throws Exception {
        MessageChannelPartitionHandler partitionHandler = new MessageChannelPartitionHandler();

        partitionHandler.setStepName("slaveStep");  // this is a bean id that we're going to execute on the slave JVMs
        partitionHandler.setGridSize(GRID_SIZE);
        partitionHandler.setMessagingOperations(messagingTemplate); // this is the mechanism with which we're sending messages
        partitionHandler.setPollInterval(5000L);    // configuring the poll interval to be 5 seconds
        partitionHandler.setJobExplorer(this.jobExplorer);
        partitionHandler.afterPropertiesSet();

        return partitionHandler;
    }

    @Bean
    public ColumnRangePartitioner partitioner() {
        ColumnRangePartitioner columnRangePartitioner = new ColumnRangePartitioner();

        columnRangePartitioner.setColumn("id");
        columnRangePartitioner.setDataSource(this.dataSource);
        columnRangePartitioner.setTable("customer");

        return columnRangePartitioner;
    }

    /*  The other new peace to this is a StepExecutionRequestHandler.
        When we execute the slave within a context of a new jvm, it's not being executed within a scope of a job.
        We're not launching a new job in each slave jvm, we're launching just an independent step.
        So, we need some way to do that. In this StepExecutionRequestHandler is how we do it.

        This StepExecutionRequestHandler is going to be listening on a Spring Integration channel, in this case
        "inboundRequests". Every message that comes in is going to execute the step that is requested.
        In our case it's called "slaveStep". Once that's done, we'll send the response back on this outboundChannel.
        In our case the outboundChannel is the null channel. It's just going to dropped. We're going to ignore it.

        So to configure this, we create our instance. The StepExecutionRequestHandler needs a way to find
        that slave step. In our case we're providing the bean id in the request, so we're using this
        BeanFactoryStepLocator().
     */
    @Bean
    @Profile("slave")
    @ServiceActivator(inputChannel = "inboundRequests", outputChannel = "outboundStaging")
    public StepExecutionRequestHandler stepExecutionRequestHandler() {
        StepExecutionRequestHandler stepExecutionRequestHandler =
                new StepExecutionRequestHandler();

        BeanFactoryStepLocator stepLocator = new BeanFactoryStepLocator();
        stepLocator.setBeanFactory(this.applicationContext);    // we're including the context we're running in
        stepExecutionRequestHandler.setStepLocator(stepLocator);    // we use this to find the step to run
        stepExecutionRequestHandler.setJobExplorer(this.jobExplorer); // we use this to obey the same context that the
                                                                    // job would if it was about to launch this step
                                                                    // so, is this step already running somewhere else.
                                                                    // It needs to validate that.

        return stepExecutionRequestHandler;
    }

    @Bean(name = PollerMetadata.DEFAULT_POLLER)
    public PollerMetadata defaultPoller() {
        PollerMetadata pollerMetadata = new PollerMetadata();
        pollerMetadata.setTrigger(new PeriodicTrigger(10));
        return pollerMetadata;
    }

    @Bean
    @StepScope
    public JdbcPagingItemReader<Customer> pagingItemReader(
            @Value("#{stepExecutionContext['minValue']}") Long minValue,
            @Value("#{stepExecutionContext['maxValue']}") Long maxValue) {
        System.out.println("reading " + minValue + " to " + maxValue);
        JdbcPagingItemReader<Customer> reader = new JdbcPagingItemReader<>();

        reader.setDataSource(this.dataSource);
        reader.setFetchSize(1000);
        reader.setRowMapper(new CustomerRowMapper());

        PostgresPagingQueryProvider queryProvider = new PostgresPagingQueryProvider();
        queryProvider.setSelectClause("id, firstName, lastName, birthdate");
        queryProvider.setFromClause("from customer");
        queryProvider.setWhereClause("where id >= " + minValue + " and id < " + maxValue);

        Map<String, Order> sortKeys = new HashMap<>(1);
        sortKeys.put("id", Order.ASCENDING);
        queryProvider.setSortKeys(sortKeys);

        reader.setQueryProvider(queryProvider);

        return reader;
    }

    @Bean
    public JdbcBatchItemWriter<Customer> customerItemWriter() {
        JdbcBatchItemWriter<Customer> itemWriter = new JdbcBatchItemWriter<>();

        itemWriter.setDataSource(this.dataSource);
        itemWriter.setSql("INSERT INTO NEW_CUSTOMER VALUES (:id, :firstName, :lastName, :birthdate)");
        itemWriter.setItemSqlParameterSourceProvider(new BeanPropertyItemSqlParameterSourceProvider());
        itemWriter.afterPropertiesSet();

        return itemWriter;
    }

    @Bean
    public Step step1() throws Exception {
        return stepBuilderFactory.get("step1")
                .partitioner(slaveStep().getName(), partitioner())
                .step(slaveStep())
                .partitionHandler(partitionHandler(null))
                .allowStartIfComplete(true)
                .build();
    }

    @Bean
    public Step slaveStep() {
        return stepBuilderFactory.get("slaveStep")
                .<Customer, Customer>chunk(1000)
                .reader(pagingItemReader(null, null))
                .writer(customerItemWriter())
                .allowStartIfComplete(true)
                .build();
    }

    @Bean
    @Profile("master")
    public Job job() throws Exception {
        return jobBuilderFactory.get("job")
                .start(step1())
                .build();
    }

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }
}
