package ru.allstreets.developer.telegram;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import ru.allstreets.developer.PostgresTestBase;
import ru.allstreets.developer.checkpoint.TaskChatRepository;
import ru.allstreets.developer.checkpoint.TaskRepository;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Restart из checkpoint ({@code markRunning}) не должен пересоздавать задачу — иначе
 * merge через {@code register} обнулил бы сохранённые {@code title}/{@code repo}.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class ActiveTaskRegistryTest extends PostgresTestBase {

    @Autowired
    private TaskRepository taskRepo;
    @Autowired
    private TaskChatRepository taskChatRepo;

    private ActiveTaskRegistry registry() {
        return new ActiveTaskRegistry(taskRepo, taskChatRepo);
    }

    @Test
    void markRunning_preservesTitleAndRepo() {
        var registry = registry();
        registry.register(1L, "t1", "исходное описание", "Моя задача", "allstreets/backend");

        registry.markRunning("t1");

        var task = taskRepo.findById("t1").orElseThrow();
        assertEquals("RUNNING", task.getStatus());
        assertEquals("Моя задача", task.getTitle(), "title не должен затираться");
        assertEquals("allstreets/backend", task.getRepo(), "repo не должен затираться");
    }
}
