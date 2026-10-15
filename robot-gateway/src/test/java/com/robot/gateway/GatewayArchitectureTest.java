package com.robot.gateway;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "com.robot.gateway",
        importOptions = ImportOption.DoNotIncludeTests.class)
class GatewayArchitectureTest {
    @ArchTest
    static final ArchRule GATEWAY_STAYS_REACTIVE_AND_OUTSIDE_BUSINESS_STORAGE =
            noClasses()
                    .that()
                    .resideInAPackage("com.robot.gateway..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "com.robot.system..",
                            "com.robot.auth..",
                            "com.robot.common.mybatis..",
                            "com.robot.common.web..",
                            "org.springframework.web.servlet..",
                            "jakarta.servlet..",
                            "java.sql..",
                            "javax.sql..",
                            "com.baomidou.mybatisplus..")
                    .because("网关保持WebFlux接入职责，不依赖Servlet或业务数据库");
}
