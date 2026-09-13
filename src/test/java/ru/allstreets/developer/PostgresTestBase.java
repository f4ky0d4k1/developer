package ru.allstreets.developer;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * База для интеграционных тестов, требующих реального PostgreSQL. Контейнер
 * {@code postgres:18} (тот же образ, что и в docker-compose) стартует один раз в
 * статическом инициализаторе и переиспользуется всеми наследниками на протяжении
 * всего JVM-прогона (очистка — через Ryuk по завершении JVM). Ручной запуск вместо
 * {@code @Container} — чтобы контейнер не останавливался {@code TestcontainersExtension}
 * между тестовыми классами, наследующими общий static-контейнер.
 */
public abstract class PostgresTestBase {

    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18")
            .withDatabaseName("opencode_test")
            .withUsername("developer")
            .withPassword("developer");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
    }
}
