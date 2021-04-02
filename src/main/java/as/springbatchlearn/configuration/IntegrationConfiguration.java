package as.springbatchlearn.configuration;

/*
    Essentially, what we're going to configure is a round trip. So, we're going to start in our master, we're going to
    walk through the components out to the slave and then back to the master.
    In most chunk-based steps you don't need to know the internal components of how Spring Batch manages the chunk.
    However, when you're dealing with remote chunking, that becomes important, the way a chunk-based step works
    is actually something called the chunk-oriented tasklet within Spring Batch. So a chunk-based step is actually
    a tasklet step. The only difference is that has a special tasklet that knows how to handle a chunk.
    Within the chunk-oriented tasklet there is something called the ChunkHandler. The ChunkHandler is used to handle
    the item processing in item writing phases of a chunk-based step. So really what happens is the reader reads in
    a chunks *** of data and it passes it to the chunk handler for processing and writing. We're going to replace
    that chunk handler with one that knows how to pass that data off to middleware to slaves for processing,
    and that also how to manage the responses.
*/

import as.springbatchlearn.domain.Customer;
import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.batch.core.step.item.SimpleChunkProcessor;
import org.springframework.batch.core.step.tasklet.TaskletStep;
import org.springframework.batch.integration.chunk.ChunkHandler;
import org.springframework.batch.integration.chunk.ChunkMessageChannelItemWriter;
import org.springframework.batch.integration.chunk.ChunkProcessorChunkHandler;
import org.springframework.batch.integration.chunk.RemoteChunkHandlerFactoryBean;
import org.springframework.batch.item.ItemProcessor;
import org.springframework.batch.item.ItemWriter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.integration.amqp.inbound.AmqpInboundChannelAdapter;
import org.springframework.integration.amqp.outbound.AmqpOutboundEndpoint;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.core.MessagingTemplate;
import org.springframework.integration.scheduling.PollerMetadata;
import org.springframework.messaging.MessageChannel;
import org.springframework.scheduling.support.PeriodicTrigger;

@Configuration
public class IntegrationConfiguration {

    public static final String CHUNKING_REQUESTS = "chunking.requests";
    public static final String CHUNKING_REPLIES = "chunking.replies";

    /*
        We do this with the RemoteChunkHandlerFactoryBean.
        This factory bean, when Spring Batch finds it will replace the normal chunk handler that is within the
        chunk tasklet. The factory bean actually uses the ItemWriter to send the messages. So we're going to set that.
        So the factoryBean.setChunkWriter is going to set a special ItemWriter that is going to write the records out
        to a middleware. We also configure a step we want to execute. It will use that to introspect to find out
        what the ItemProcessor and ItemWriter are for the appropriate need. We then return the object.
    */
    @Bean
    public ChunkHandler chunkHandler(TaskletStep step1) throws Exception {
        RemoteChunkHandlerFactoryBean factoryBean = new RemoteChunkHandlerFactoryBean();

        factoryBean.setChunkWriter(chunkWriter());
        factoryBean.setStep(step1);

        return factoryBean.getObject();
    }

    /*
        For our chunking we use this chunk message item writer. This is what's responsible for actually sending
        the message from the master to the slave. We set the messageTemplate, so we use the Spring Integration
        Messaging Template for the mechanism of doing the message sending. So, we're going to send that.
        Within the messageTemplate is also when we're configure the channel that were sending the messages out to.
        We listen for the responses back on this reply channel (inboundReplies). We'll be sending them out
        on a channel called outboundRequests. We set max timeouts (it's the maximum number of timeouts we're going to
        tolerate. And then we return our writer.
    */
    @Bean
    public ChunkMessageChannelItemWriter chunkWriter() {
        ChunkMessageChannelItemWriter chunkWriter = new ChunkMessageChannelItemWriter();

        chunkWriter.setMessagingOperations(messageTemplate());
        chunkWriter.setReplyChannel(inboundReplies());
        chunkWriter.setMaxWaitTimeouts(10);

        return chunkWriter;
    }

    @Bean
    public MessagingTemplate messageTemplate() {
        MessagingTemplate messagingTemplate = new MessagingTemplate(outboundRequests());

        messagingTemplate.setReceiveTimeout(60000000l);

        return messagingTemplate;
    }

    /*
        We're using Rabbit for our persistent communication in this example.
        This is what's going to be responsible for actually sending the data from our channel to Rabbit.
    */
    @Bean
    @ServiceActivator(inputChannel = "outboundRequests")
    public AmqpOutboundEndpoint amqpOutboundEndpoint(AmqpTemplate template) {
        AmqpOutboundEndpoint endpoint = new AmqpOutboundEndpoint(template);

        endpoint.setExpectReply(false);
        endpoint.setOutputChannel(inboundReplies());

        endpoint.setRoutingKey(CHUNKING_REQUESTS);

        return endpoint;
    }

    // Here we declare our channel itself
    @Bean
    public MessageChannel outboundRequests() {
        return new DirectChannel();
    }

    @Bean
    public Queue requestQueue() {
        return new Queue(CHUNKING_REQUESTS, false);
    }

    /*
        On the slave JVM, we have to listen for the messages the master sends.
        This is going to receive the messages from Rabbit and write them to this inboundRequestChannel.
        So, we have outboundRequest in the master and inboundRequest in the slave.
    */
    @Bean
    @Profile("slave")
    public AmqpInboundChannelAdapter inboundRequestsAdapter(SimpleMessageListenerContainer listenerContainer) {
        AmqpInboundChannelAdapter adapter = new AmqpInboundChannelAdapter(listenerContainer);

        adapter.setOutputChannel(inboundRequests());

        adapter.afterPropertiesSet();

        return adapter;
    }

    // We declare our message channel
    @Bean
    public MessageChannel inboundRequests() {
        return new DirectChannel();
    }

    /*
        We mentioned before that the chunk processor is the peace within Spring Batch that knows how to
        process a chunk. The ChunkProcessorChunkHandler wraps that so that it can be called when messages arrive
        from the master. So, every time the message comes in this component knows how to listen for that and calls
        the SimpleChunkProcessor when the message arrives. So we're going to create our SimpleChunkProcessor
        which is the exact same component that Spring Batch would use if that was not a remote chunking example.
        We set our ItemProcessor and ItemWriter, we call for property set, and now we're going to create our
        ChunkProcessorChunkHandler. This is that wrapper we're just mentioned. We set our chunk processor
        in the chunk handler, called afterPropertiesSet, and returned chunkHandler.
    */
    @Bean
    @Profile("slave")
    @ServiceActivator(inputChannel = "inboundRequests", outputChannel = "outboundReplies")
    public ChunkProcessorChunkHandler chunkProcessorChunkHandler(ItemProcessor<Customer, Customer> itemProcessor, ItemWriter<Customer> itemWriter) throws Exception {
        SimpleChunkProcessor chunkProcessor = new SimpleChunkProcessor<>(itemProcessor, itemWriter);
        chunkProcessor.afterPropertiesSet();

        ChunkProcessorChunkHandler<Customer> chunkHandler = new ChunkProcessorChunkHandler<>();

        chunkHandler.setChunkProcessor(chunkProcessor);
        chunkHandler.afterPropertiesSet();

        return chunkHandler;
    }

    @Bean
    public QueueChannel outboundReplies() {
        return new QueueChannel();
    }

    /*
        On the Reply side of things, once the chunk has been processed, we need to reply that something is happened.
        And so we'll do that over the outboundReplies channel. And just like in the master, where we had
        AmqpOutboundEndpoint sending messages from the master to the slave, we've got the same thing sending messages
        from the slave back to the master. We go ahead to create our endpoint, we configure our routing key to be
        CHUNKING_REPLIES, (so we have CHUNKING_REQUESTS in the way IN and CHUNKING_REPLIES in the way OUT),
        and return the endpoint.
    */
    @Bean
    @Profile("slave")
    @ServiceActivator(inputChannel = "outboundReplies")
    public AmqpOutboundEndpoint amqpOutboundEndpointReplies(AmqpTemplate template) {
        AmqpOutboundEndpoint endpoint = new AmqpOutboundEndpoint(template);

        endpoint.setExpectReply(false);

        endpoint.setRoutingKey(CHUNKING_REPLIES);

        return endpoint;
    }

    @Bean
    public Queue replyQueue() {
        return new Queue(CHUNKING_REPLIES, false);
    }

    // This is out channel of messages coming back in from the slave
    @Bean
    public QueueChannel inboundReplies() {
        return new QueueChannel();
    }

    /*
        This is listening to the messages from Rabbit on the master side to be received.
    */
    @Bean
    @Profile("master")
    public AmqpInboundChannelAdapter inboundRepliesAdapter(SimpleMessageListenerContainer listenerContainer) {
        AmqpInboundChannelAdapter adapter = new AmqpInboundChannelAdapter(listenerContainer);

        adapter.setOutputChannel(inboundReplies());

        adapter.afterPropertiesSet();

        return adapter;
    }

    @Bean
    @Profile("slave")
    public SimpleMessageListenerContainer requestContainer(ConnectionFactory connectionFactory) {
        SimpleMessageListenerContainer container =
                new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(CHUNKING_REQUESTS);
        container.setAutoStartup(false);

        return container;
    }

    @Bean
    @Profile("master")
    public SimpleMessageListenerContainer replyContainer(ConnectionFactory connectionFactory) {
        SimpleMessageListenerContainer container =
                new SimpleMessageListenerContainer(connectionFactory);
        container.setQueueNames(CHUNKING_REPLIES);
        container.setAutoStartup(false);

        return container;
    }

    @Bean(name = PollerMetadata.DEFAULT_POLLER)
    public PollerMetadata defaultPoller() {
        PollerMetadata pollerMetadata = new PollerMetadata();
        pollerMetadata.setTrigger(new PeriodicTrigger(10));
        return pollerMetadata;
    }
}
