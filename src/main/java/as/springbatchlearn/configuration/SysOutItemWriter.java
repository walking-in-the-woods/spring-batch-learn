package as.springbatchlearn.configuration;

import org.springframework.batch.item.ItemWriter;

import java.util.List;

public class SysOutItemWriter implements ItemWriter<String> {

    @Override
    public void write(List<? extends String> items) throws Exception {
        System.out.println("The size of this chunk was: " + items.size());

        for (String item : items) {
            System.out.println(">> " + item);
        }
    }
}
