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
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

@Configuration
@MapperScan(
    basePackages = "com.costlink.report.mapper.reimbursement",
    sqlSessionTemplateRef = "reimbursementSqlSessionTemplate"
)
public class ReimbursementDbConfig {

    @Bean
    @Primary
    @ConfigurationProperties("costlink.report.datasources.reimbursement")
    public DataSource reimbursementDS() {
        return new HikariDataSource();
    }

    @Bean
    @Primary
    public SqlSessionFactory reimbursementSqlSessionFactory(
            @Qualifier("reimbursementDS") DataSource ds) throws Exception {
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(ds);
        return bean.getObject();
    }

    @Bean
    @Primary
    public SqlSessionTemplate reimbursementSqlSessionTemplate(
            @Qualifier("reimbursementSqlSessionFactory") SqlSessionFactory sf) {
        return new SqlSessionTemplate(sf);
    }
}
