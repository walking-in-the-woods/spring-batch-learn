package as.springbatchlearn;

import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@EnableBatchProcessing
public class SpringBatchLearnApplication {

	public static void main(String[] args) {
		SpringApplication.run(SpringBatchLearnApplication.class, args);
	}

}
