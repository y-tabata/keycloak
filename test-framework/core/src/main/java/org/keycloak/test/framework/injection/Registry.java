package org.keycloak.test.framework.injection;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.keycloak.test.framework.TestFrameworkExtension;
import org.keycloak.test.framework.config.Config;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.stream.Collectors;

@SuppressWarnings({"rawtypes", "unchecked"})
public class Registry implements ExtensionContext.Store.CloseableResource {

    private RegistryLogger logger;

    private ExtensionContext currentContext;
    private final List<Supplier<?, ?>> suppliers = new LinkedList<>();
    private final List<InstanceContext<?, ?>> deployedInstances = new LinkedList<>();
    private final List<RequestedInstance<?, ?>> requestedInstances = new LinkedList<>();

    public Registry() {
        loadSuppliers();
    }

    public ExtensionContext getCurrentContext() {
        return currentContext;
    }

    public void setCurrentContext(ExtensionContext currentContext) {
        this.currentContext = currentContext;
    }

    public <T> T getDependency(Class<T> typeClass, String ref, InstanceContext dependent) {
        ref = StringUtil.convertEmptyToNull(ref);
        T dependency;
        dependency = getDeployedDependency(typeClass, ref, dependent);
        if (dependency != null) {
            return dependency;
        } else {
            dependency = getRequestedDependency(typeClass, ref, dependent);
            if(dependency != null) {
                return dependency;
            } else {
                dependency = getUnConfiguredDependency(typeClass, ref, dependent);
                if(dependency != null) {
                    return dependency;
                }
            }
        }

        throw new RuntimeException("Dependency not found: " + typeClass);
    }

    public List<InstanceContext<?, ?>> getDeployedInstances() {
        return deployedInstances;
    }

    public List<RequestedInstance<?, ?>> getRequestedInstances() {
        return requestedInstances;
    }

    private <T> T getDeployedDependency(Class<T> typeClass, String ref, InstanceContext dependent) {
        InstanceContext dependency = getDeployedInstance(typeClass, ref);
        if (dependency != null) {
            dependency.registerDependency(dependent);

            logger.logDependencyInjection(dependent, dependency, RegistryLogger.InjectionType.EXISTING);

            return (T) dependency.getValue();
        }
        return null;
    }

    private <T> T getRequestedDependency(Class<T> typeClass, String ref, InstanceContext dependent) {
        RequestedInstance requestedDependency = getRequestedInstance(typeClass, ref);
        if (requestedDependency != null) {
            InstanceContext dependency = new InstanceContext<Object, Annotation>(requestedDependency.getInstanceId(), this, requestedDependency.getSupplier(), requestedDependency.getAnnotation(), requestedDependency.getValueType());
            dependency.setValue(requestedDependency.getSupplier().getValue(dependency));
            dependency.registerDependency(dependent);
            deployedInstances.add(dependency);

            requestedInstances.remove(requestedDependency);

            logger.logDependencyInjection(dependent, dependency, RegistryLogger.InjectionType.REQUESTED);

            return (T) dependency.getValue();
        }
        return null;
    }

    private <T> T getUnConfiguredDependency(Class<T> typeClass, String ref, InstanceContext dependent) {
        InstanceContext dependency;
        Optional<Supplier<?, ?>> supplied = suppliers.stream().filter(s -> s.getValueType().equals(typeClass)).findFirst();
        if (supplied.isPresent()) {
            Supplier<T, ?> supplier = (Supplier<T, ?>) supplied.get();
            Annotation defaultAnnotation = DefaultAnnotationProxy.proxy(supplier.getAnnotationClass());
            dependency = new InstanceContext(-1, this, supplier, defaultAnnotation, typeClass);

            dependency.registerDependency(dependent);
            dependency.setValue(supplier.getValue(dependency));

            deployedInstances.add(dependency);

            logger.logDependencyInjection(dependent, dependency, RegistryLogger.InjectionType.UN_CONFIGURED);

            return (T) dependency.getValue();
        }
        return null;
    }

    public void beforeEach(Object testInstance) {
        findRequestedInstances(testInstance);
        matchDeployedInstancesWithRequestedInstances();
        deployRequestedInstances();
        injectFields(testInstance);
        invokeBeforeEachOnSuppliers();
    }

    private void findRequestedInstances(Object testInstance) {
        Class testClass = testInstance.getClass();
        RequestedInstance requestedServerInstance = createRequestedInstance(testClass.getAnnotations(), null);
        if (requestedServerInstance != null) {
            requestedInstances.add(requestedServerInstance);
        }

        for (Field f : listFields(testClass)) {
            RequestedInstance requestedInstance = createRequestedInstance(f.getAnnotations(), f.getType());
            if (requestedInstance != null) {
                requestedInstances.add(requestedInstance);
            }
        }

        logger.logRequestedInstances(requestedInstances);
    }

    private void matchDeployedInstancesWithRequestedInstances() {
        Iterator<RequestedInstance<?, ?>> itr = requestedInstances.iterator();
        while (itr.hasNext()) {
            RequestedInstance<?, ?> requestedInstance = itr.next();
            InstanceContext deployedInstance = getDeployedInstance(requestedInstance);
            if (deployedInstance != null) {
                if (requestedInstance.getLifeCycle().equals(deployedInstance.getLifeCycle()) && deployedInstance.getSupplier().compatible(deployedInstance, requestedInstance)) {
                    logger.logReusingCompatibleInstance(deployedInstance);
                    itr.remove();
                } else {
                    logger.logDestroyIncompatible(deployedInstance);
                    destroy(deployedInstance);
                }
            }
        }
    }

    private void deployRequestedInstances() {
        requestedInstances.sort(RequestedInstanceComparator.INSTANCE);
        while (!requestedInstances.isEmpty()) {
            RequestedInstance requestedInstance = requestedInstances.remove(0);

            if (getDeployedInstance(requestedInstance) == null) {
                InstanceContext instance = new InstanceContext(requestedInstance.getInstanceId(), this, requestedInstance.getSupplier(), requestedInstance.getAnnotation(), requestedInstance.getValueType());
                instance.setValue(requestedInstance.getSupplier().getValue(instance));
                deployedInstances.add(instance);

                logger.logCreatedInstance(requestedInstance, instance);
            }
        }
    }

    private void injectFields(Object testInstance) {
        for (Field f : listFields(testInstance.getClass())) {
            InstanceContext<?, ?> instance = getDeployedInstance(f.getType(), f.getAnnotations());
            if(instance == null) { // a test class might have fields not meant for injection
                continue;
            }
            try {
                f.setAccessible(true);
                f.set(testInstance, instance.getValue());
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public void afterAll() {
        logger.logAfterAll();
        List<InstanceContext<?, ?>> destroy = deployedInstances.stream().filter(i -> i.getLifeCycle().equals(LifeCycle.CLASS)).toList();
        destroy.forEach(this::destroy);
    }

    public void afterEach() {
        logger.logAfterEach();
        List<InstanceContext<?, ?>> destroy = deployedInstances.stream().filter(i -> i.getLifeCycle().equals(LifeCycle.METHOD)).toList();
        destroy.forEach(this::destroy);

        List<InstanceContext<?, ?>> cleanup = deployedInstances.stream().filter(i -> i.getValue() instanceof ManagedTestResource).toList();
        for (InstanceContext<?, ?> c : cleanup) {
            ManagedTestResource managedTestResource = (ManagedTestResource) c.getValue();
            if (managedTestResource.isDirty()) {
                logger.logDestroyDirty(c);
                destroy(c);
            } else {
                logger.logCleanup(c);
                managedTestResource.runCleanup();
            }
        }
    }

    public void close() {
        logger.logClose();
        List<InstanceContext<?, ?>> destroy = deployedInstances.stream().sorted(InstanceContextComparator.INSTANCE.reversed()).toList();
        destroy.forEach(this::destroy);
    }

    List<Supplier<?, ?>> getSuppliers() {
        return suppliers;
    }

    private RequestedInstance<?, ?> createRequestedInstance(Annotation[] annotations, Class<?> valueType) {
        for (Annotation a : annotations) {
            for (Supplier s : suppliers) {
                if (s.getAnnotationClass().equals(a.annotationType())) {
                    return new RequestedInstance(s, a, valueType);
                }
            }
        }
        return null;
    }

    private InstanceContext<?, ?> getDeployedInstance(Class<?> valueType, Annotation[] annotations) {
        for (Annotation a : annotations) {
            for (InstanceContext<?, ?> i : deployedInstances) {
                Supplier supplier = i.getSupplier();
                if (supplier.getAnnotationClass().equals(a.annotationType())
                        && valueType.isAssignableFrom(i.getValue().getClass())
                        && Objects.equals(supplier.getRef(a), i.getRef()) ) {
                    return i;
                }
            }
        }
        return null;
    }

    private void destroy(InstanceContext instanceContext) {
        boolean removed = deployedInstances.remove(instanceContext);
        if (removed) {
            Set<InstanceContext> dependencies = instanceContext.getDependencies();
            dependencies.forEach(this::destroy);
            instanceContext.getSupplier().close(instanceContext);

            logger.logDestroy(instanceContext);
        }
    }

    private InstanceContext getDeployedInstance(RequestedInstance requestedInstance) {
        String requestedRef = requestedInstance.getRef();
        Class requestedValueType = requestedInstance.getValueType();
        for (InstanceContext<?, ?> i : deployedInstances) {
            if(!Objects.equals(i.getRef(), requestedRef)) {
                continue;
            }

            if (requestedValueType != null) {
                if (requestedValueType.isAssignableFrom(i.getValue().getClass())) {
                    return i;
                }
            } else if (i.getSupplier().equals(requestedInstance.getSupplier())) {
                return i;
            }
        }
        return null;
    }

    private void loadSuppliers() {
        Iterator<TestFrameworkExtension> extensions = ServiceLoader.load(TestFrameworkExtension.class).iterator();
        ValueTypeAlias valueTypeAlias = new ValueTypeAlias();
        List<Supplier> tmp = new LinkedList<>();
        while (extensions.hasNext()) {
            TestFrameworkExtension extension = extensions.next();
            tmp.addAll(extension.suppliers());
            valueTypeAlias.addAll(extension.valueTypeAliases());
        }

        logger = new RegistryLogger(valueTypeAlias);

        Set<Class> loadedValueTypes = new HashSet<>();
        Set<Supplier> skippedSuppliers = new HashSet<>();

        for (Supplier supplier : tmp) {
            boolean shouldAdd = false;
            Class supplierValueType = supplier.getValueType();

            if (!loadedValueTypes.contains(supplierValueType)) {
                String requestedSupplier = Config.getSelectedSupplier(supplierValueType, valueTypeAlias);
                if (requestedSupplier != null) {
                    if (requestedSupplier.equals(supplier.getAlias())) {
                        shouldAdd = true;
                    }
                } else {
                    shouldAdd = true;
                }
            }

            if (shouldAdd) {
                suppliers.add(supplier);
                loadedValueTypes.add(supplierValueType);
            } else {
                skippedSuppliers.add(supplier);
            }
        }

        logger.logSuppliers(suppliers, skippedSuppliers);
    }

    private InstanceContext getDeployedInstance(Class typeClass, String ref) {
        return deployedInstances.stream()
                .filter(i -> i.getSupplier().getValueType().equals(typeClass) && Objects.equals(i.getRef(), ref))
                .findFirst().orElse(null);
    }

    private RequestedInstance getRequestedInstance(Class typeClass, String ref) {
        return requestedInstances.stream()
                .filter(i -> i.getSupplier().getValueType().equals(typeClass) && Objects.equals(i.getRef(), ref))
                .findFirst().orElse(null);
    }

    private void invokeBeforeEachOnSuppliers() {
        for (InstanceContext i : deployedInstances) {
            i.getSupplier().onBeforeEach(i);
        }
    }

    private List<Field> listFields(Class clazz) {
        List<Field> fields = new LinkedList<>(Arrays.asList(clazz.getDeclaredFields()));

        Class<?> superclass = clazz.getSuperclass();
        while (superclass != null && !superclass.equals(Object.class)) {
            fields.addAll(Arrays.asList(superclass.getDeclaredFields()));
            superclass = superclass.getSuperclass();
        }

        return fields;
    }

    private static class RequestedInstanceComparator implements Comparator<RequestedInstance> {

        static final RequestedInstanceComparator INSTANCE = new RequestedInstanceComparator();

        @Override
        public int compare(RequestedInstance o1, RequestedInstance o2) {
            return Integer.compare(o1.getSupplier().order(), o2.getSupplier().order());
        }
    }

    private static class InstanceContextComparator implements Comparator<InstanceContext> {

        static final InstanceContextComparator INSTANCE = new InstanceContextComparator();

        @Override
        public int compare(InstanceContext o1, InstanceContext o2) {
            return Integer.compare(o1.getSupplier().order(), o2.getSupplier().order());
        }
    }

}