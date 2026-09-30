package io.github.martiaaguilera.quantarun.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
class SchemaMigrationTest {

    @Autowired
    Flyway flyway;

    @Autowired
    JdbcClient jdbc;

    @Test
    void migrate_onEmptyDatabase_appliesEveryMigrationWithoutPending() {
        var info = flyway.info();

        assertThat(info.applied()).isNotEmpty();
        assertThat(info.pending()).isEmpty();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void projects_rejectInvalidNamesAndWeightsAtTheDatabaseLevel() {
        assertThatThrownBy(() -> insertProject("Not Valid!", 1)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertProject("zero-weight", 0)).isInstanceOf(DataIntegrityViolationException.class);

        insertProject("schema-test", 3);
        assertThatThrownBy(() -> insertProject("schema-test", 3)).isInstanceOf(DataIntegrityViolationException.class);
    }

    private void insertProject(String name, int weight) {
        jdbc.sql("INSERT INTO projects (name, weight) VALUES (:name, :weight)")
                .param("name", name)
                .param("weight", weight)
                .update();
    }
}
