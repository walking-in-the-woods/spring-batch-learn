package as.springbatchlearn.configuration;

/* Usage:

1. mvn clean install
2. java -jar target/spring-batch-learn-0.0.1-SNAPSHOT.jar message=Hello!

You can write any message you want instead of "Hello!"
 */

import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class JobParametersConfiguration {

    @Autowired
    private JobBuilderFactory jobBuilderFactory;

    @Autowired
    private StepBuilderFactory stepBuilderFactory;

    @Bean
    @StepScope // Using @StepScope annotation we create bean instances instead of using a singleton
    public Tasklet messageTasklet(@Value("#{jobParameters['message']}") String message) {
        return (stepContribution, chunkContext) -> {
            System.out.println(message);
            return RepeatStatus.FINISHED;
        };
    }

    @Bean
    public Step step1() {
        // we use here null as param value because this method will never be called,
        // Spring is going to use it essentially as a reference to the bean that is generated
        // from the messageTasklet()
        return stepBuilderFactory.get("step1")
                .tasklet(messageTasklet(null))
                .build();
    }

    @Bean
    public Job jobParametersJob() {
        return jobBuilderFactory.get("jobParametersJob")
                .start(step1())
                .build();
    }
}
