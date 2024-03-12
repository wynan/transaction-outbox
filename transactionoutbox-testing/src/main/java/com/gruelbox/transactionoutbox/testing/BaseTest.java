package com.gruelbox.transactionoutbox.testing;

import com.gruelbox.transactionoutbox.*;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.SQLException;
import lombok.Builder;
import lombok.Value;
import lombok.experimental.Accessors;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

@Slf4j
public abstract class BaseTest {

  protected HikariDataSource dataSource;

  @BeforeEach
  final void baseBeforeEach() {
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(connectionDetails().url());
    config.setUsername(connectionDetails().user());
    config.setPassword(connectionDetails().password());
    config.addDataSourceProperty("cachePrepStmts", "true");
    dataSource = new HikariDataSource(config);
  }

  @AfterEach
  final void baseAfterEach() {
    dataSource.close();
  }

  protected ConnectionDetails connectionDetails() {
    return ConnectionDetails.builder()
        .dialect(Dialect.H2)
        .driverClassName("org.h2.Driver")
        .url(
            "jdbc:h2:mem:test;DB_CLOSE_DELAY=-1;DEFAULT_LOCK_TIMEOUT=60000;LOB_TIMEOUT=2000;MV_STORE=TRUE;DATABASE_TO_UPPER=FALSE")
        .user("test")
        .password("test")
        .build();
  }

  protected TransactionManager txManager() {
    return TransactionManager.fromDataSource(dataSource);
  }

  protected Persistor persistor() {
    return Persistor.forDialect(connectionDetails().dialect());
  }

  protected void clearOutbox() {
    DefaultPersistor persistor = Persistor.forDialect(connectionDetails().dialect());
    TransactionManager transactionManager = txManager();
    transactionManager.inTransaction(
        tx -> {
          try {
            persistor.clear(tx);
          } catch (SQLException e) {
            throw new RuntimeException(e);
          }
        });
  }

  protected void withRunningFlusher(TransactionOutbox outbox, ThrowingRunnable runnable)
      throws Exception {
    Thread backgroundThread =
        new Thread(
            () -> {
              while (!Thread.interrupted()) {
                try {
                  // Keep flushing work until there's nothing left to flush
                  //noinspection StatementWithEmptyBody
                  while (outbox.flush()) {}
                } catch (Exception e) {
                  log.error("Error flushing transaction outbox. Pausing", e);
                }
                try {
                  //noinspection BusyWait
                  Thread.sleep(250);
                } catch (InterruptedException e) {
                  break;
                }
              }
            });
    backgroundThread.start();
    try {
      runnable.run();
    } finally {
      backgroundThread.interrupt();
      backgroundThread.join();
    }
  }

  @Value
  @Accessors(fluent = true)
  @Builder
  public static class ConnectionDetails {
    String driverClassName;
    String url;
    String user;
    String password;
    Dialect dialect;
  }
}