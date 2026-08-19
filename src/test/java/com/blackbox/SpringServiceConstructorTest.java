package com.blackbox;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Service;

import java.lang.reflect.Constructor;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpringServiceConstructorTest {

    @Test
    void everyServiceHasOneConstructorOrAnExplicitAutowiredConstructor() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Service.class));

        for (var beanDefinition : scanner.findCandidateComponents("com.blackbox")) {
            Class<?> serviceType = Class.forName(beanDefinition.getBeanClassName());
            List<Constructor<?>> constructors = Arrays.asList(serviceType.getDeclaredConstructors());
            long injectableConstructors = constructors.stream()
                    .filter(constructor -> constructor.isAnnotationPresent(Autowired.class))
                    .count();
            assertThat(constructors.size() == 1 || injectableConstructors == 1)
                    .as("%s must have one constructor or exactly one @Autowired constructor", serviceType.getName())
                    .isTrue();
        }
    }
}
