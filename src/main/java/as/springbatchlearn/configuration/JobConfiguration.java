package as.springbatchlearn.configuration;

import as.springbatchlearn.components.CustomException;
import as.springbatchlearn.components.CustomSkipListener;
import as.springbatchlearn.components.SkipItemProcessor;
import as.springbatchlearn.components.SkipItemWriter;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.Step;
import org.springframework.batch.core.configuration.annotation.JobBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepBuilderFactory;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.item.support.ListItemReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class JobConfiguration {

    @Autowired
    private JobBuilderFactory jobBuilderFactory;

    @Autowired
    private StepBuilderFactory stepBuilderFactory;

    @Bean
    @StepScope
    public ListItemReader reader() {

        List<String> items = new ArrayList<>();

        for(int i = 0; i < 100; i++) {
            items.add(String.valueOf(i));
        }

        return new ListItemReader<>(items);
    }

    @Bean
    @StepScope
    public SkipItemProcessor processor() {
        return new SkipItemProcessor();
    }

    @Bean
    @StepScope
    public SkipItemWriter writer() {
        return new SkipItemWriter();
    }

    @Bean
    public Step step1() throws Exception {
        return stepBuilderFactory.get("step1")
                .<String, String>chunk(10)
                .reader(reader())
                .processor(processor())
                .writer(writer())
                .faultTolerant()
                .skip(CustomException.class)
                .skipLimit(15)
                .listener(new CustomSkipListener())
                .build();
    }

    @Bean
    public Job job() throws Exception {
        return jobBuilderFactory.get("job")
                .start(step1())
                .build();
    }
}
