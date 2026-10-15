package com.robot.system.contract;

import com.baomidou.mybatisplus.autoconfigure.MybatisPlusAutoConfiguration;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import javax.sql.DataSource;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Executed outside Maven with production classes and dependencies from the packaged Boot JAR. */
public final class PackagedMapperProbe {
    private PackagedMapperProbe() {}

    /**
     * Assemble every artifact mapper and verify injected BaseMapper statements without external
     * infrastructure.
     *
     * @param arguments command-line arguments; the probe requires none
     * @throws Exception if the application context or a mapper statement cannot be assembled
     */
    public static void main(String[] arguments) throws Exception {
        ProbeArguments parsed = parseArguments(arguments);
        try (var context =
                new AnnotationConfigApplicationContext(OfflineMapperConfiguration.class)) {
            var configuration = context.getBean(SqlSessionFactory.class).getConfiguration();
            List<Map<String, Object>> verified =
                    verifyMappers(context, configuration, parsed.mapperNames());
            verifyRegistry(configuration, parsed.mapperNames());
            new ObjectMapper()
                    .writeValue(
                            parsed.output().toFile(),
                            Map.of("mappers", verified, "externalConnections", 0));
        }
    }

    private static ProbeArguments parseArguments(String[] arguments) {
        if (arguments.length < 2) {
            throw new IllegalArgumentException("output file and mapper classes required");
        }
        return new ProbeArguments(
                Path.of(arguments[0]), Arrays.asList(arguments).subList(1, arguments.length));
    }

    private static List<Map<String, Object>> verifyMappers(
            AnnotationConfigApplicationContext context,
            org.apache.ibatis.session.Configuration configuration,
            List<String> mapperNames)
            throws Exception {
        List<Map<String, Object>> verified = new ArrayList<>();
        for (String name : mapperNames) {
            verifyMapperBean(context, name);
            verified.add(
                    Map.of("mapper", name, "statements", verifyStatements(configuration, name)));
        }
        return verified;
    }

    private static void verifyMapperBean(AnnotationConfigApplicationContext context, String name)
            throws Exception {
        Class<?> mapperType = Class.forName(name);
        if (!mapperType.isAnnotationPresent(Mapper.class)
                || !BaseMapper.class.isAssignableFrom(mapperType)) {
            throw new IllegalStateException("unmodeled mapper type: " + name);
        }
        Object bean = context.getBean(mapperType);
        if (!mapperType.isInstance(bean)) {
            throw new IllegalStateException("mapper is not assembled: " + name);
        }
    }

    private static List<String> verifyStatements(
            org.apache.ibatis.session.Configuration configuration, String name) {
        List<String> statements = new ArrayList<>();
        for (String method : List.of("insert", "selectById", "updateById", "deleteById")) {
            String statement = name + "." + method;
            if (!configuration.hasStatement(statement)) {
                throw new IllegalStateException("missing mapper statement: " + statement);
            }
            statements.add(statement);
        }
        return statements;
    }

    private static void verifyRegistry(
            org.apache.ibatis.session.Configuration configuration, List<String> mapperNames) {
        TreeSet<String> actual = new TreeSet<>();
        for (Class<?> type : configuration.getMapperRegistry().getMappers()) {
            actual.add(type.getName());
        }
        if (!actual.equals(new TreeSet<>(mapperNames))) {
            throw new IllegalStateException("packaged mapper registry drift: " + actual);
        }
    }

    private record ProbeArguments(Path output, List<String> mapperNames) {}

    // Deferred auto-configuration sees this fixture DataSource; no @Component/@Configuration scan
    // leakage.
    @ImportAutoConfiguration(MybatisPlusAutoConfiguration.class)
    @MapperScan(basePackages = "com.robot.system", annotationClass = Mapper.class)
    static class OfflineMapperConfiguration {
        @Bean
        DataSource dataSource() {
            DriverManagerDataSource source = new DriverManagerDataSource();
            source.setDriverClassName("org.h2.Driver");
            source.setUrl("jdbc:h2:mem:packaged_mapper_probe;MODE=MySQL;DB_CLOSE_DELAY=-1");
            source.setUsername("sa");
            source.setPassword("");
            return source;
        }
    }
}
