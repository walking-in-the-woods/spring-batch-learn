package as.springbatchlearn.configuration;

import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.integration.amqp.inbound.AmqpInboundChannelAdapter;
import org.springframework.integration.amqp.outbound.AmqpOutboundEndpoint;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.channel.NullChannel;
import org.springframework.integration.channel.QueueChannel;
import org.springframework.integration.core.MessagingTemplate;
import org.springframework.messaging.PollableChannel;

@Configuration
public class IntegrationConfiguration {

    //  Tis is used in the MessageChannelPartitionHandler to send messages.
    @Bean
    public MessagingTemplate messagingTemplate() {
        MessagingTemplate messagingTemplate = new MessagingTemplate(outboundRequests());

        messagingTemplate.setReceiveTimeout(60000000L); // 1 hour

        return messagingTemplate;
    }

    @Bean
    public DirectChannel outboundRequests() {
        return new DirectChannel();
    }

    /*  We're sending messages on to this channel. And this channel is being listen to by the AmqpOutboundEndpoint
        This peace of code that is going to listen on the outboundRequestChannel, and when the message is coming in
        it's going to send a message via Rabbit to a particular queue. In this case, it will send it to
        "partition.request" queue. We'll be listening on that queue in the slave JVMs for work, so we created our
        AmqpOutboundEndpoint, we configured it properly and returned it.
    */
    @Bean
    @ServiceActivator(inputChannel = "outboundRequests")
    public AmqpOutboundEndpoint amqpOutboundEndpoint(AmqpTemplate template) {
        AmqpOutboundEndpoint endpoint = new AmqpOutboundEndpoint(template);

        endpoint.setExpectReply(true);
        endpoint.setOutputChannel(inboundRequests());
        endpoint.setRoutingKey("partition.requests");

        return endpoint;
    }

    // This is the RabbitMQ queue definition
    @Bean
    public Queue requestQueue() {
        return new Queue("partition.requests", false);
    }

    /*  In the slave JVMs, there are a few additional components.
        This peace of code is going to be listening for Rabbit to send us messages. That will take the messages off
        of Rabbit and than pass them onto the internal components in the slave JVMs for processing.
        We set up our listener container. The listener container is the peace of code that is managing the listening.
        We set our outboundChannel wherein our sending those messages that we're taking off of Rabbit,
        in this case it's "inboundRequests". We call afterPropertiesSet and return the adapter.
    */
    @Bean
    @Profile("slave")
    public AmqpInboundChannelAdapter inbound(SimpleMessageListenerContainer listenerContainer) {
        AmqpInboundChannelAdapter adapter = new AmqpInboundChannelAdapter(listenerContainer);

        adapter.setOutputChannel(inboundRequests());
        adapter.afterPropertiesSet();

        return adapter;
    }

    /*  This is that message container. We configured that with a ConnectionFactory to Rabbit.
        The queue name to listen to in this case is "partition.requests". We set auto start to false.
        (If we don't set autostart to false we may see examples where we have multiple slave JVMs running
        and only one is picking up work. So this prevents this condition from happening). Then we return the container.
    */
    @Bean
    public SimpleMessageListenerContainer container(ConnectionFactory connectionFactory) {
        SimpleMessageListenerContainer container =
                new SimpleMessageListenerContainer(connectionFactory);

        container.setQueueNames("partition.requests");
        container.setAutoStartup(false);

        return container;
    }

    /*  Essentially the way this works is: in a master JVM we have outbound request channel that goes of a Rabbit.
        Then it's picked up in a slave JVM and it goes on inboundRequests. inboundRequests is listen to by the
        StepExecutionRequestHandler. The reply of the StepExecutionRequestHandler is in written on the outboundStaging.
        outboundStaging is a null channel which is actually mean that responses are dropped. Those responses are
        dropped because we don't need them because we're simply polling the job repository for results.
    */
    @Bean
    public PollableChannel outboundStaging() {
        return new NullChannel();
    }

    @Bean
    public QueueChannel inboundRequests() {
        return new QueueChannel();
    }
}
