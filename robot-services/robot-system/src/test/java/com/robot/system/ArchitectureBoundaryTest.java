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
    static final ArchRule USER_HTTP_DOES_NOT_USE_PERSISTENCE_MODELS =
            noClasses()
                    .that()
                    .resideInAPackage("com.robot.system.user.controller..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAPackage("com.robot.system.user.entity..")
                    .because("用户HTTP入口通过DTO和VO表达协议，数据库实体属于业务持久化模型");

    @ArchTest
    static final ArchRule USER_INPUTS_AND_ENTITIES_DO_NOT_USE_EXECUTION_LAYERS =
            noClasses()
                    .that()
                    .resideInAnyPackage(
                            "com.robot.system.user.dto..", "com.robot.system.user.entity..")
                    .should()
                    .dependOnClassesThat()
                    .resideInAnyPackage(
                            "com.robot.system..controller..",
                            "com.robot.system..service..",
                            "com.robot.system..mapper..")
                    .because("请求与数据模型不编排用例，不反向依赖控制器、事务服务或数据访问组件");

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
