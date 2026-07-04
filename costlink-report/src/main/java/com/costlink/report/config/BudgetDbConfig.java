package com.costlink.report.config;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
@MapperScan(
    basePackages = "com.costlink.report.mapper.budget",
    sqlSessionTemplateRef = "budgetSqlSessionTemplate"
)
public class BudgetDbConfig {

    @Bean
    @ConfigurationProperties("costlink.report.datasources.budget")
    public DataSource budgetDS() {
        return new HikariDataSource();
    }

    @Bean
    public SqlSessionFactory budgetSqlSessionFactory(
            @Qualifier("budgetDS") DataSource ds) throws Exception {
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(ds);
        return bean.getObject();
    }

    @Bean
    public SqlSessionTemplate budgetSqlSessionTemplate(
            @Qualifier("budgetSqlSessionFactory") SqlSessionFactory sf) {
        return new SqlSessionTemplate(sf);
    }
}
