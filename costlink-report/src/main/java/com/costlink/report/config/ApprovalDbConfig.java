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
    basePackages = "com.costlink.report.mapper.approval",
    sqlSessionTemplateRef = "approvalSqlSessionTemplate"
)
public class ApprovalDbConfig {

    @Bean
    @ConfigurationProperties("costlink.report.datasources.approval")
    public DataSource approvalDS() {
        return new HikariDataSource();
    }

    @Bean
    public SqlSessionFactory approvalSqlSessionFactory(
            @Qualifier("approvalDS") DataSource ds) throws Exception {
        SqlSessionFactoryBean bean = new SqlSessionFactoryBean();
        bean.setDataSource(ds);
        return bean.getObject();
    }

    @Bean
    public SqlSessionTemplate approvalSqlSessionTemplate(
            @Qualifier("approvalSqlSessionFactory") SqlSessionFactory sf) {
        return new SqlSessionTemplate(sf);
    }
}
