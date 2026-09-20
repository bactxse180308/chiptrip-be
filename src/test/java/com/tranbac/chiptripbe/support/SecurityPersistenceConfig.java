package com.tranbac.chiptripbe.support;

import com.tranbac.chiptripbe.module.auth.repository.OtpCodeRepository;
import com.tranbac.chiptripbe.module.user.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.*;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.MSSQLServerContainer;
import javax.sql.DataSource;
import java.util.Map;
import java.util.UUID;

/** No application config, .env, provider clients or startup schema repair is loaded. */
@Configuration
@EnableTransactionManagement
@EnableJpaRepositories(basePackageClasses = {OtpCodeRepository.class, UserRepository.class})
public class SecurityPersistenceConfig implements DisposableBean {
    private MSSQLServerContainer<?> sqlServer;

    @Bean DataSource dataSource() {
        String database = System.getProperty("chiptrip.test.database", "h2");
        if ("sqlserver".equals(database)) {
            sqlServer = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2022-latest").acceptLicense();
            sqlServer.start();
            return new DriverManagerDataSource(sqlServer.getJdbcUrl(), sqlServer.getUsername(), sqlServer.getPassword());
        }
        if (!"h2".equals(database)) throw new IllegalArgumentException("Unknown test database: " + database);
        return new DriverManagerDataSource("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MSSQLServer;DB_CLOSE_DELAY=-1", "sa", "");
    }

    @Bean LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
        var bean = new LocalContainerEntityManagerFactoryBean();
        bean.setDataSource(dataSource);
        bean.setPackagesToScan("com.tranbac.chiptripbe.module.auth.entity", "com.tranbac.chiptripbe.module.user.entity");
        bean.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
        // Each context owns a fresh database; do not attempt to drop nonexistent SQL Server constraints.
        bean.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "create-only", "hibernate.hbm2ddl.halt_on_error", "true"));
        return bean;
    }

    @Bean PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
        return new JpaTransactionManager(factory);
    }

    @Override public void destroy() {
        if (sqlServer != null) sqlServer.stop();
    }
}
