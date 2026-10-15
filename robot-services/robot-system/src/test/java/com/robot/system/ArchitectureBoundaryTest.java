package com.robot.system;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/** 对编译字节码检查真实依赖，防止日后绕过服务与契约层。 */
@AnalyzeClasses(packages = "com.robot", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureBoundaryTest {
    @ArchTest
    static final ArchRule CONTROLLERS_USE_SERVICES =
            noClasses()
                    .that()
                    .resideInAPackage("com.robot.system..controller..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "com.robot.system..mapper..",
                            "org.springframework.jdbc..",
                            "org.springframework.data.redis..",
                            "org.redisson..",
                            "com.baomidou.mybatisplus.core.conditions..")
                    .because("Web层只绑定参数和响应，查询作用域、授权、事务在Service执行");

    @ArchTest
    static final ArchRule SHARED_AND_CONTRACTS_DO_NOT_USE_SERVICE_IMPLEMENTATIONS =
            noClasses()
                    .that()
                    .resideInAnyPackage("com.robot.common..", "com.robot.api..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage("com.robot.system..", "com.robot.auth..")
                    .because("公共组件与跨服务契约不能依赖服务实现");

    @ArchTest
    static final ArchRule SYSTEM_DOES_NOT_IMPORT_AUTH_IMPLEMENTATION =
            noClasses()
                    .that()
                    .resideInAPackage("com.robot.system..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("com.robot.auth..")
                    .because("服务间使用robot-api-system契约和RPC，禁止实现jar依赖");
}
