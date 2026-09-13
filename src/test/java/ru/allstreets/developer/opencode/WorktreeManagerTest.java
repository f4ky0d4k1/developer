package ru.allstreets.developer.opencode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorktreeManager#prepareSlot} не должен молча запускать агента в пустой
 * директории без репозитория (именно так задача жгла бюджет вхолостую).
 */
class WorktreeManagerTest {

    @TempDir
    Path workDir;

    private WorktreeManager manager() {
        return new WorktreeManager(workDir.toString(), "", 2);
    }

    @Test
    void prepareSlot_withoutRepo_failsFast() {
        var ex = assertThrows(IllegalStateException.class, () -> manager().prepareSlot(0, null));
        assertTrue(ex.getMessage().contains("TARGET_REPO"), "сообщение должно объяснять причину: " + ex.getMessage());

        assertThrows(IllegalStateException.class, () -> manager().prepareSlot(0, ""));
        assertThrows(IllegalStateException.class, () -> manager().prepareSlot(0, "   "));
    }
}
