package io.quarkus.resteasy.reactive.server.deployment;

import static java.util.stream.Collectors.toList;
import static org.jboss.resteasy.reactive.common.processor.ResteasyReactiveDotNames.DATE_FORMAT;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Stream;

import javax.ws.rs.Priorities;
import javax.ws.rs.RuntimeType;
import javax.ws.rs.core.Application;
import javax.ws.rs.core.MediaType;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.AnnotationValue;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;
import org.jboss.jandex.Type;
import org.jboss.resteasy.reactive.ResponseHeader;
import org.jboss.resteasy.reactive.ResponseStatus;
import org.jboss.resteasy.reactive.common.core.Serialisers;
import org.jboss.resteasy.reactive.common.core.SingletonBeanFactory;
import org.jboss.resteasy.reactive.common.model.InjectableBean;
import org.jboss.resteasy.reactive.common.model.ResourceClass;
import org.jboss.resteasy.reactive.common.model.ResourceDynamicFeature;
import org.jboss.resteasy.reactive.common.model.ResourceFeature;
import org.jboss.resteasy.reactive.common.model.ResourceInterceptors;
import org.jboss.resteasy.reactive.common.model.ResourceReader;
import org.jboss.resteasy.reactive.common.model.ResourceWriter;
import org.jboss.resteasy.reactive.common.processor.AdditionalReaderWriter;
import org.jboss.resteasy.reactive.common.processor.AdditionalReaders;
import org.jboss.resteasy.reactive.common.processor.AdditionalWriters;
import org.jboss.resteasy.reactive.common.processor.DefaultProducesHandler;
import org.jboss.resteasy.reactive.common.processor.EndpointIndexer;
import org.jboss.resteasy.reactive.common.processor.ResteasyReactiveDotNames;
import org.jboss.resteasy.reactive.common.processor.scanning.ApplicationScanningResult;
import org.jboss.resteasy.reactive.common.processor.scanning.ResourceScanningResult;
import org.jboss.resteasy.reactive.common.processor.transformation.AnnotationStore;
import org.jboss.resteasy.reactive.common.processor.transformation.AnnotationsTransformer;
import org.jboss.resteasy.reactive.common.util.Encode;
import org.jboss.resteasy.reactive.server.core.Deployment;
import org.jboss.resteasy.reactive.server.core.DeploymentInfo;
import org.jboss.resteasy.reactive.server.core.ExceptionMapping;
import org.jboss.resteasy.reactive.server.core.ServerSerialisers;
import org.jboss.resteasy.reactive.server.handlers.PublisherResponseHandler;
import org.jboss.resteasy.reactive.server.handlers.ResponseHandler;
import org.jboss.resteasy.reactive.server.model.ContextResolvers;
import org.jboss.resteasy.reactive.server.model.DynamicFeatures;
import org.jboss.resteasy.reactive.server.model.Features;
import org.jboss.resteasy.reactive.server.model.FixedResponseBuilderAndStreamingResponseCustomizer;
import org.jboss.resteasy.reactive.server.model.HandlerChainCustomizer;
import org.jboss.resteasy.reactive.server.model.ParamConverterProviders;
import org.jboss.resteasy.reactive.server.model.ServerMethodParameter;
import org.jboss.resteasy.reactive.server.model.ServerResourceMethod;
import org.jboss.resteasy.reactive.server.processor.scanning.MethodScanner;
import org.jboss.resteasy.reactive.server.processor.util.ResteasyReactiveServerDotNames;
import org.jboss.resteasy.reactive.spi.BeanFactory;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.deployment.BeanArchiveIndexBuildItem;
import io.quarkus.arc.deployment.BeanContainerBuildItem;
import io.quarkus.arc.deployment.GeneratedBeanBuildItem;
import io.quarkus.arc.deployment.UnremovableBeanBuildItem;
import io.quarkus.arc.runtime.BeanContainer;
import io.quarkus.arc.runtime.ClientProxyUnwrapper;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.Capability;
import io.quarkus.deployment.Feature;
import io.quarkus.deployment.GeneratedClassGizmoAdaptor;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.annotations.ExecutionTime;
import io.quarkus.deployment.annotations.Record;
import io.quarkus.deployment.builditem.ApplicationClassPredicateBuildItem;
import io.quarkus.deployment.builditem.BytecodeTransformerBuildItem;
import io.quarkus.deployment.builditem.CombinedIndexBuildItem;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.GeneratedClassBuildItem;
import io.quarkus.deployment.builditem.LaunchModeBuildItem;
import io.quarkus.deployment.builditem.RecordableConstructorBuildItem;
import io.quarkus.deployment.builditem.ShutdownContextBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveClassBuildItem;
import io.quarkus.deployment.builditem.nativeimage.ReflectiveHierarchyBuildItem;
import io.quarkus.deployment.recording.RecorderContext;
import io.quarkus.gizmo.ClassCreator;
import io.quarkus.gizmo.MethodCreator;
import io.quarkus.netty.deployment.MinNettyAllocatorMaxOrderBuildItem;
import io.quarkus.resteasy.reactive.common.deployment.ApplicationResultBuildItem;
import io.quarkus.resteasy.reactive.common.deployment.FactoryUtils;
import io.quarkus.resteasy.reactive.common.deployment.QuarkusFactoryCreator;
import io.quarkus.resteasy.reactive.common.deployment.QuarkusResteasyReactiveDotNames;
import io.quarkus.resteasy.reactive.common.deployment.ResourceInterceptorsBuildItem;
import io.quarkus.resteasy.reactive.common.deployment.ResourceScanningResultBuildItem;
import io.quarkus.resteasy.reactive.common.deployment.SerializersUtil;
import io.quarkus.resteasy.reactive.common.deployment.ServerDefaultProducesHandlerBuildItem;
import io.quarkus.resteasy.reactive.common.runtime.ResteasyReactiveConfig;
import io.quarkus.resteasy.reactive.server.runtime.ResteasyReactiveInitialiser;
import io.quarkus.resteasy.reactive.server.runtime.ResteasyReactiveRecorder;
import io.quarkus.resteasy.reactive.server.runtime.ResteasyReactiveRuntimeRecorder;
import io.quarkus.resteasy.reactive.server.runtime.ResteasyReactiveServerRuntimeConfig;
import io.quarkus.resteasy.reactive.server.runtime.ServerMutinyAsyncFileMessageBodyWriter;
import io.quarkus.resteasy.reactive.server.runtime.ServerVertxAsyncFileMessageBodyWriter;
import io.quarkus.resteasy.reactive.server.runtime.ServerVertxBufferMessageBodyWriter;
import io.quarkus.resteasy.reactive.server.runtime.exceptionmappers.AuthenticationCompletionExceptionMapper;
import io.quarkus.resteasy.reactive.server.runtime.exceptionmappers.AuthenticationFailedExceptionMapper;
import io.quarkus.resteasy.reactive.server.runtime.exceptionmappers.AuthenticationRedirectExceptionMapper;
import io.quarkus.resteasy.reactive.server.runtime.exceptionmappers.ForbiddenExceptionMapper;
import io.quarkus.resteasy.reactive.server.runtime.exceptionmappers.UnauthorizedExceptionMapper;
import io.quarkus.resteasy.reactive.server.runtime.security.EagerSecurityHandler;
import io.quarkus.resteasy.reactive.server.runtime.security.SecurityContextOverrideHandler;
import io.quarkus.resteasy.reactive.server.spi.AnnotationsTransformerBuildItem;
import io.quarkus.resteasy.reactive.server.spi.MethodScannerBuildItem;
import io.quarkus.resteasy.reactive.spi.CustomExceptionMapperBuildItem;
import io.quarkus.resteasy.reactive.spi.DynamicFeatureBuildItem;
import io.quarkus.resteasy.reactive.spi.ExceptionMapperBuildItem;
import io.quarkus.resteasy.reactive.spi.JaxrsFeatureBuildItem;
import io.quarkus.resteasy.reactive.spi.MessageBodyReaderBuildItem;
import io.quarkus.resteasy.reactive.spi.MessageBodyReaderOverrideBuildItem;
import io.quarkus.resteasy.reactive.spi.MessageBodyWriterBuildItem;
import io.quarkus.resteasy.reactive.spi.MessageBodyWriterOverrideBuildItem;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.RuntimeValue;
import io.quarkus.security.AuthenticationCompletionException;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.AuthenticationRedirectException;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.vertx.http.deployment.RouteBuildItem;
import io.quarkus.vertx.http.runtime.HttpBuildTimeConfig;
import io.quarkus.vertx.http.runtime.VertxHttpRecorder;
import io.vertx.core.Handler;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;

public class ResteasyReactiveProcessor {

    private static final String QUARKUS_INIT_CLASS = "io.quarkus.rest.runtime.__QuarkusInit";

    private static final DotName RESPONSE_HEADER = DotName.createSimple(ResponseHeader.class.getName());
    private static final DotName RESPONSE_HEADER_LIST = DotName.createSimple(ResponseHeader.List.class.getName());
    private static final DotName RESPONSE_STATUS = DotName.createSimple(ResponseStatus.class.getName());

    private static final Set<DotName> CONTEXT_TYPES = Set.of(
            DotName.createSimple(HttpServerRequest.class.getName()),
            DotName.createSimple(HttpServerResponse.class.getName()),
            DotName.createSimple(RoutingContext.class.getName()));

    @BuildStep
    public FeatureBuildItem buildSetup() {
        return new FeatureBuildItem(Feature.RESTEASY_REACTIVE);
    }

    // This is required to get rid of netty exceptions when allocating direct buffers in tests running
    // in IDEs, which have assertions enabled, otherwise we run into:
    /*
     * java.lang.AssertionError
     * at io.netty.buffer.PoolChunk.calculateRunSize(PoolChunk.java:366)
     * at io.netty.buffer.PoolChunk.allocateSubpage(PoolChunk.java:424)
     * at io.netty.buffer.PoolChunk.allocate(PoolChunk.java:299)
     * at io.netty.buffer.PoolArena.allocateNormal(PoolArena.java:205)
     * at io.netty.buffer.PoolArena.tcacheAllocateSmall(PoolArena.java:174)
     * at io.netty.buffer.PoolArena.allocate(PoolArena.java:136)
     * at io.netty.buffer.PoolArena.allocate(PoolArena.java:128)
     *
     * at io.netty.buffer.PooledByteBufAllocator.newDirectBuffer(PooledByteBufAllocator.java:378)
     * at io.netty.buffer.AbstractByteBufAllocator.directBuffer(AbstractByteBufAllocator.java:187)
     * at io.netty.buffer.AbstractByteBufAllocator.directBuffer(AbstractByteBufAllocator.java:178)
     * at io.vertx.core.net.impl.PartialPooledByteBufAllocator.directBuffer(PartialPooledByteBufAllocator.java:92)
     */
    @BuildStep
    MinNettyAllocatorMaxOrderBuildItem setMinimalNettyMaxOrderSize() {
        return new MinNettyAllocatorMaxOrderBuildItem(3);
    }

    @BuildStep
    void recordableConstructor(BuildProducer<RecordableConstructorBuildItem> ctors) {
        ctors.produce(new RecordableConstructorBuildItem(ServerResourceMethod.class));
        ctors.produce(new RecordableConstructorBuildItem(ServerMethodParameter.class));
    }

    @BuildStep
    MethodScannerBuildItem responseStatusSupport() {
        return new MethodScannerBuildItem(new MethodScanner() {
            @Override
            public List<HandlerChainCustomizer> scan(MethodInfo method, ClassInfo actualEndpointClass,
                    Map<String, Object> methodContext) {
                AnnotationStore annotationStore = (AnnotationStore) methodContext
                        .get(EndpointIndexer.METHOD_CONTEXT_ANNOTATION_STORE);
                AnnotationInstance annotationInstance = annotationStore.getAnnotation(method, RESPONSE_STATUS);
                if (annotationInstance == null) {
                    return Collections.emptyList();
                }
                AnnotationValue responseStatusValue = annotationInstance.value();
                if (responseStatusValue == null) {
                    return Collections.emptyList();
                }
                int statusCode = responseStatusValue.asInt();
                ResponseHandler.ResponseBuilderCustomizer.StatusCustomizer responseBuilderCustomizer = new ResponseHandler.ResponseBuilderCustomizer.StatusCustomizer();
                responseBuilderCustomizer.setStatus(statusCode);
                PublisherResponseHandler.StreamingResponseCustomizer.StatusCustomizer streamingResponseCustomizer = new PublisherResponseHandler.StreamingResponseCustomizer.StatusCustomizer();
                streamingResponseCustomizer.setStatus(statusCode);
                return Collections.singletonList(new FixedResponseBuilderAndStreamingResponseCustomizer(
                        responseBuilderCustomizer, streamingResponseCustomizer));
            }
        });
    }

    @BuildStep
    MethodScannerBuildItem responseHeaderSupport() {
        return new MethodScannerBuildItem(new MethodScanner() {
            @Override
            public List<HandlerChainCustomizer> scan(MethodInfo method, ClassInfo actualEndpointClass,
                    Map<String, Object> methodContext) {
                AnnotationStore annotationStore = (AnnotationStore) methodContext
                        .get(EndpointIndexer.METHOD_CONTEXT_ANNOTATION_STORE);
                AnnotationInstance responseHeaderInstance = annotationStore.getAnnotation(method, RESPONSE_HEADER);
                AnnotationInstance responseHeadersInstance = annotationStore.getAnnotation(method, RESPONSE_HEADER_LIST);
                if ((responseHeaderInstance == null) && (responseHeadersInstance == null)) {
                    return Collections.emptyList();
                }
                List<AnnotationInstance> instances = new ArrayList<>();
                if (responseHeaderInstance != null) {
                    instances.add(responseHeaderInstance);
                }
                if (responseHeadersInstance != null) {
                    AnnotationValue value = responseHeadersInstance.value();
                    if (value != null) {
                        instances.addAll(Arrays.asList(value.asNestedArray()));
                    }
                }
                Map<String, List<String>> headers = new HashMap<>();
                for (AnnotationInstance headerInstance : instances) {
                    headers.put(headerInstance.value("name").asString(),
                            Arrays.asList(headerInstance.value("value").asStringArray()));
                }
                ResponseHandler.ResponseBuilderCustomizer.AddHeadersCustomizer responseBuilderCustomizer = new ResponseHandler.ResponseBuilderCustomizer.AddHeadersCustomizer();
                responseBuilderCustomizer.setHeaders(headers);
                PublisherResponseHandler.StreamingResponseCustomizer.AddHeadersCustomizer streamingResponseCustomizer = new PublisherResponseHandler.StreamingResponseCustomizer.AddHeadersCustomizer();
                streamingResponseCustomizer.setHeaders(headers);
                return Collections.singletonList(new FixedResponseBuilderAndStreamingResponseCustomizer(
                        responseBuilderCustomizer, streamingResponseCustomizer));
            }
        });
    }

    @BuildStep
    void vertxIntegration(BuildProducer<MessageBodyWriterBuildItem> writerBuildItemBuildProducer) {
        writerBuildItemBuildProducer.produce(new MessageBodyWriterBuildItem(ServerVertxBufferMessageBodyWriter.class.getName(),
                Buffer.class.getName(), Collections.singletonList(MediaType.WILDCARD), RuntimeType.SERVER, true,
                Priorities.USER));
        writerBuildItemBuildProducer
                .produce(new MessageBodyWriterBuildItem(ServerVertxAsyncFileMessageBodyWriter.class.getName(),
                        io.vertx.core.file.AsyncFile.class.getName(), Collections.singletonList(MediaType.WILDCARD),
                        RuntimeType.SERVER, true,
                        Priorities.USER));
        writerBuildItemBuildProducer
                .produce(new MessageBodyWriterBuildItem(ServerMutinyAsyncFileMessageBodyWriter.class.getName(),
                        io.vertx.mutiny.core.file.AsyncFile.class.getName(), Collections.singletonList(MediaType.WILDCARD),
                        RuntimeType.SERVER, true,
                        Priorities.USER));
    }

    @BuildStep
    void generateCustomProducer(Optional<ResourceScanningResultBuildItem> resourceScanningResultBuildItem,
            BuildProducer<GeneratedBeanBuildItem> generatedBeanBuildItemBuildProducer,
            BuildProducer<AdditionalBeanBuildItem> additionalBeanBuildItemBuildProducer) {
        if (!resourceScanningResultBuildItem.isPresent()) {
            return;
        }

        Map<DotName, MethodInfo> resourcesThatNeedCustomProducer = resourceScanningResultBuildItem.get().getResult()
                .getResourcesThatNeedCustomProducer();
        Set<String> beanParams = resourceScanningResultBuildItem.get().getResult()
                .getBeanParams();
        if (!resourcesThatNeedCustomProducer.isEmpty() || !beanParams.isEmpty()) {
            CustomResourceProducersGenerator.generate(resourcesThatNeedCustomProducer, beanParams,
                    generatedBeanBuildItemBuildProducer,
                    additionalBeanBuildItemBuildProducer);
        }
    }

    @BuildStep
    void handleClassLevelExceptionMappers(Optional<ResourceScanningResultBuildItem> resourceScanningResultBuildItem,
            BuildProducer<GeneratedClassBuildItem> generatedClass,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClass,
            BuildProducer<ClassLevelExceptionMappersBuildItem> classLevelExceptionMappers) {
        if (!resourceScanningResultBuildItem.isPresent()) {
            return;
        }
        List<MethodInfo> methodExceptionMapper = resourceScanningResultBuildItem.get().getResult()
                .getClassLevelExceptionMappers();
        if (methodExceptionMapper.isEmpty()) {
            return;
        }
        GeneratedClassGizmoAdaptor classOutput = new GeneratedClassGizmoAdaptor(generatedClass, true);
        final Map<DotName, Map<String, String>> resultingMappers = new HashMap<>(methodExceptionMapper.size());
        for (MethodInfo methodInfo : methodExceptionMapper) {
            Map<String, String> generationResult = ServerExceptionMapperGenerator.generatePerClassMapper(methodInfo,
                    classOutput);
            reflectiveClass.produce(
                    new ReflectiveClassBuildItem(true, false, false, generationResult.values().toArray(new String[0])));
            Map<String, String> classMappers;
            DotName classDotName = methodInfo.declaringClass().name();
            if (resultingMappers.containsKey(classDotName)) {
                classMappers = resultingMappers.get(classDotName);
            } else {
                classMappers = new HashMap<>();
                resultingMappers.put(classDotName, classMappers);
            }
            classMappers.putAll(generationResult);
        }
        classLevelExceptionMappers.produce(new ClassLevelExceptionMappersBuildItem(resultingMappers));
    }

    @BuildStep
    void registerCustomExceptionMappers(BuildProducer<CustomExceptionMapperBuildItem> customExceptionMapper) {
        customExceptionMapper.produce(new CustomExceptionMapperBuildItem(AuthenticationFailedExceptionMapper.class.getName()));
        customExceptionMapper.produce(new CustomExceptionMapperBuildItem(UnauthorizedExceptionMapper.class.getName()));
    }

    @BuildStep
    public void unremoveableBeans(Optional<ResourceScanningResultBuildItem> resourceScanningResultBuildItem,
            BuildProducer<UnremovableBeanBuildItem> unremoveableBeans) {
        if (!resourceScanningResultBuildItem.isPresent()) {
            return;
        }
        Set<String> beanParams = resourceScanningResultBuildItem.get().getResult()
                .getBeanParams();
        unremoveableBeans.produce(UnremovableBeanBuildItem.beanClassNames(beanParams.toArray(new String[0])));
    }

    @BuildStep
    //note useIdentityComparisonForParameters=false
    //resteasy can generate lots of small collections with similar values as part of its metadata gathering
    //this allows multiple objects to be compressed into a single object at runtime
    //saving memory and reducing reload time
    @Record(value = ExecutionTime.STATIC_INIT, useIdentityComparisonForParameters = false)
    public void setupEndpoints(BeanArchiveIndexBuildItem beanArchiveIndexBuildItem,
            BeanContainerBuildItem beanContainerBuildItem,
            ResteasyReactiveConfig config,
            Optional<ResourceScanningResultBuildItem> resourceScanningResultBuildItem,
            BuildProducer<GeneratedClassBuildItem> generatedClassBuildItemBuildProducer,
            BuildProducer<BytecodeTransformerBuildItem> bytecodeTransformerBuildItemBuildProducer,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClassBuildItemBuildProducer,
            ResteasyReactiveRecorder recorder,
            List<ServerDefaultProducesHandlerBuildItem> serverDefaultProducesHandlers,
            Optional<ClassLevelExceptionMappersBuildItem> classLevelExceptionMappers,
            BuildProducer<SetupEndpointsResultBuildItem> setupEndpointsResultProducer,
            BuildProducer<ResteasyReactiveResourceMethodEntriesBuildItem> resourceMethodEntriesBuildItemBuildProducer,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClass,
            BuildProducer<ReflectiveHierarchyBuildItem> reflectiveHierarchy,
            ApplicationResultBuildItem applicationResultBuildItem,
            ParamConverterProvidersBuildItem paramConverterProvidersBuildItem,
            List<ApplicationClassPredicateBuildItem> applicationClassPredicateBuildItems,
            List<MethodScannerBuildItem> methodScanners,
            List<AnnotationsTransformerBuildItem> annotationTransformerBuildItems)
            throws NoSuchMethodException {

        if (!resourceScanningResultBuildItem.isPresent()) {
            // no detected @Path, bail out
            return;
        }

        IndexView index = beanArchiveIndexBuildItem.getIndex();

        ResourceScanningResult result = resourceScanningResultBuildItem.get().getResult();
        Map<DotName, ClassInfo> scannedResources = result.getScannedResources();
        Map<DotName, String> scannedResourcePaths = result.getScannedResourcePaths();
        Map<DotName, String> pathInterfaces = result.getPathInterfaces();

        ApplicationScanningResult appResult = applicationResultBuildItem.getResult();
        Set<String> singletonClasses = appResult.getSingletonClasses();

        Map<String, String> existingConverters = new HashMap<>();
        List<ResourceClass> resourceClasses = new ArrayList<>();
        List<ResourceClass> subResourceClasses = new ArrayList<>();
        AdditionalReaders additionalReaders = new AdditionalReaders();
        AdditionalWriters additionalWriters = new AdditionalWriters();
        Map<String, InjectableBean> injectableBeans = new HashMap<>();
        QuarkusServerEndpointIndexer serverEndpointIndexer;

        ParamConverterProviders paramConverterProviders = paramConverterProvidersBuildItem.getParamConverterProviders();
        Function<String, BeanFactory<?>> factoryFunction = s -> FactoryUtils.factory(s, singletonClasses, recorder,
                beanContainerBuildItem);
        paramConverterProviders.initializeDefaultFactories(factoryFunction);
        paramConverterProviders.sort();

        try (ClassCreator c = new ClassCreator(new GeneratedClassGizmoAdaptor(generatedClassBuildItemBuildProducer, true),
                QUARKUS_INIT_CLASS, null, Object.class.getName(), ResteasyReactiveInitialiser.class.getName());
                MethodCreator initConverters = c.getMethodCreator("init", void.class, Deployment.class)) {

            List<ResteasyReactiveResourceMethodEntriesBuildItem.Entry> resourceMethodEntries = new ArrayList<>();

            Predicate<String> applicationClassPredicate = s -> {
                for (ApplicationClassPredicateBuildItem i : applicationClassPredicateBuildItems) {
                    if (i.test(s)) {
                        return true;
                    }
                }
                return false;
            };
            QuarkusServerEndpointIndexer.Builder serverEndpointIndexerBuilder = new QuarkusServerEndpointIndexer.Builder()
                    .addMethodScanners(
                            methodScanners.stream().map(MethodScannerBuildItem::getMethodScanner).collect(toList()))
                    .setIndex(index)
                    .addContextTypes(CONTEXT_TYPES)
                    .setFactoryCreator(new QuarkusFactoryCreator(recorder, beanContainerBuildItem.getValue()))
                    .setEndpointInvokerFactory(new QuarkusInvokerFactory(generatedClassBuildItemBuildProducer, recorder))
                    .setGeneratedClassBuildItemBuildProducer(generatedClassBuildItemBuildProducer)
                    .setBytecodeTransformerBuildProducer(bytecodeTransformerBuildItemBuildProducer)
                    .setReflectiveClassProducer(reflectiveClassBuildItemBuildProducer)
                    .setExistingConverters(existingConverters)
                    .setScannedResourcePaths(scannedResourcePaths)
                    .setConfig(createRestReactiveConfig(config))
                    .setAdditionalReaders(additionalReaders)
                    .setHttpAnnotationToMethod(result.getHttpAnnotationToMethod())
                    .setInjectableBeans(injectableBeans)
                    .setAdditionalWriters(additionalWriters)
                    .setDefaultBlocking(appResult.getBlockingDefault())
                    .setApplicationScanningResult(appResult)
                    .setHasRuntimeConverters(!paramConverterProviders.getParamConverterProviders().isEmpty())
                    .setClassLevelExceptionMappers(
                            classLevelExceptionMappers.isPresent() ? classLevelExceptionMappers.get().getMappers()
                                    : Collections.emptyMap())
                    .setResourceMethodCallback(new Consumer<>() {
                        @Override
                        public void accept(EndpointIndexer.ResourceMethodCallbackData entry) {
                            MethodInfo method = entry.getMethodInfo();

                            resourceMethodEntries.add(new ResteasyReactiveResourceMethodEntriesBuildItem.Entry(
                                    entry.getBasicResourceClassInfo(), method,
                                    entry.getActualEndpointInfo(), entry.getResourceMethod()));

                            String source = ResteasyReactiveProcessor.class.getSimpleName() + " > " + method.declaringClass()
                                    + "[" + method + "]";

                            ClassInfo classInfoWithSecurity = consumeStandardSecurityAnnotations(method,
                                    entry.getActualEndpointInfo(), index, c -> c);
                            if (classInfoWithSecurity != null) {
                                reflectiveClass.produce(new ReflectiveClassBuildItem(false, true, false,
                                        entry.getActualEndpointInfo().name().toString()));
                            }

                            reflectiveHierarchy.produce(new ReflectiveHierarchyBuildItem.Builder()
                                    .type(method.returnType())
                                    .index(index)
                                    .ignoreTypePredicate(QuarkusResteasyReactiveDotNames.IGNORE_TYPE_FOR_REFLECTION_PREDICATE)
                                    .ignoreFieldPredicate(QuarkusResteasyReactiveDotNames.IGNORE_FIELD_FOR_REFLECTION_PREDICATE)
                                    .ignoreMethodPredicate(
                                            QuarkusResteasyReactiveDotNames.IGNORE_METHOD_FOR_REFLECTION_PREDICATE)
                                    .source(source)
                                    .build());

                            for (short i = 0; i < method.parameters().size(); i++) {
                                Type parameterType = method.parameters().get(i);
                                if (!hasAnnotation(method, i, ResteasyReactiveServerDotNames.CONTEXT)) {
                                    reflectiveHierarchy.produce(new ReflectiveHierarchyBuildItem.Builder()
                                            .type(parameterType)
                                            .index(index)
                                            .ignoreTypePredicate(
                                                    QuarkusResteasyReactiveDotNames.IGNORE_TYPE_FOR_REFLECTION_PREDICATE)
                                            .ignoreFieldPredicate(
                                                    QuarkusResteasyReactiveDotNames.IGNORE_FIELD_FOR_REFLECTION_PREDICATE)
                                            .ignoreMethodPredicate(
                                                    QuarkusResteasyReactiveDotNames.IGNORE_METHOD_FOR_REFLECTION_PREDICATE)
                                            .source(source)
                                            .build());
                                }
                            }
                        }

                        private boolean hasAnnotation(MethodInfo method, short paramPosition, DotName annotation) {
                            for (AnnotationInstance annotationInstance : method.annotations()) {
                                AnnotationTarget target = annotationInstance.target();
                                if (target != null && target.kind() == AnnotationTarget.Kind.METHOD_PARAMETER
                                        && target.asMethodParameter().position() == paramPosition
                                        && annotationInstance.name().equals(annotation)) {
                                    return true;
                                }
                            }
                            return false;
                        }
                    })
                    .setInitConverters(initConverters)
                    .setResteasyReactiveRecorder(recorder)
                    .setApplicationClassPredicate(applicationClassPredicate);

            if (!serverDefaultProducesHandlers.isEmpty()) {
                List<DefaultProducesHandler> handlers = new ArrayList<>(serverDefaultProducesHandlers.size());
                for (ServerDefaultProducesHandlerBuildItem bi : serverDefaultProducesHandlers) {
                    handlers.add(bi.getDefaultProducesHandler());
                }
                serverEndpointIndexerBuilder
                        .setDefaultProducesHandler(new DefaultProducesHandler.DelegatingDefaultProducesHandler(handlers));
            }

            if (!annotationTransformerBuildItems.isEmpty()) {
                List<AnnotationsTransformer> annotationsTransformers = new ArrayList<>(annotationTransformerBuildItems.size());
                for (AnnotationsTransformerBuildItem bi : annotationTransformerBuildItems) {
                    annotationsTransformers.add(bi.getAnnotationsTransformer());
                }
                serverEndpointIndexerBuilder.setAnnotationsTransformers(annotationsTransformers);
            }

            serverEndpointIndexerBuilder.setMultipartReturnTypeHandler(new QuarkusMultipartReturnTypeHandler(
                    generatedClassBuildItemBuildProducer, applicationClassPredicate, reflectiveClassBuildItemBuildProducer));
            serverEndpointIndexer = serverEndpointIndexerBuilder.build();

            for (ClassInfo i : scannedResources.values()) {
                Optional<ResourceClass> endpoints = serverEndpointIndexer.createEndpoints(i, true);
                if (endpoints.isPresent()) {
                    if (singletonClasses.contains(i.name().toString())) {
                        endpoints.get().setFactory(new SingletonBeanFactory<>(i.name().toString()));
                    }
                    resourceClasses.add(endpoints.get());
                }
            }
            //now index possible sub resources. These are all classes that have method annotations
            //that are not annotated @Path
            Deque<ClassInfo> toScan = new ArrayDeque<>();
            for (DotName methodAnnotation : result.getHttpAnnotationToMethod().keySet()) {
                for (AnnotationInstance instance : index.getAnnotations(methodAnnotation)) {
                    MethodInfo method = instance.target().asMethod();
                    ClassInfo classInfo = method.declaringClass();
                    toScan.add(classInfo);
                }
            }
            //sub resources can also have just a path annotation
            //if they are 'intermediate' sub resources
            for (AnnotationInstance instance : index.getAnnotations(ResteasyReactiveDotNames.PATH)) {
                if (instance.target().kind() == AnnotationTarget.Kind.METHOD) {
                    MethodInfo method = instance.target().asMethod();
                    ClassInfo classInfo = method.declaringClass();
                    toScan.add(classInfo);
                }
            }
            Map<DotName, ClassInfo> possibleSubResources = new HashMap<>();
            while (!toScan.isEmpty()) {
                ClassInfo classInfo = toScan.poll();
                if (scannedResources.containsKey(classInfo.name()) ||
                        pathInterfaces.containsKey(classInfo.name()) ||
                        possibleSubResources.containsKey(classInfo.name())) {
                    continue;
                }
                possibleSubResources.put(classInfo.name(), classInfo);
                Optional<ResourceClass> endpoints = serverEndpointIndexer.createEndpoints(classInfo, false);
                if (endpoints.isPresent()) {
                    subResourceClasses.add(endpoints.get());
                }
                //we need to also look for all sub classes and interfaces
                //they may have type variables that need to be handled
                toScan.addAll(index.getKnownDirectImplementors(classInfo.name()));
                toScan.addAll(index.getKnownDirectSubclasses(classInfo.name()));
            }

            setupEndpointsResultProducer.produce(new SetupEndpointsResultBuildItem(resourceClasses, subResourceClasses,
                    additionalReaders, additionalWriters));
            resourceMethodEntriesBuildItemBuildProducer
                    .produce(new ResteasyReactiveResourceMethodEntriesBuildItem(resourceMethodEntries));

            initConverters.returnValue(null);
        }

        handleDateFormatReflection(reflectiveClass, index);
    }

    private void handleDateFormatReflection(BuildProducer<ReflectiveClassBuildItem> reflectiveClass, IndexView index) {
        Collection<AnnotationInstance> dateFormatInstances = index.getAnnotations(DATE_FORMAT);
        if (dateFormatInstances.isEmpty()) {
            return;
        }
        List<String> dateTimeFormatterProviderClassNames = new ArrayList<>();
        for (AnnotationInstance instance : dateFormatInstances) {
            AnnotationValue dateTimeFormatterProviderValue = instance.value("dateTimeFormatterProvider");
            if (dateTimeFormatterProviderValue != null) {
                dateTimeFormatterProviderClassNames.add(dateTimeFormatterProviderValue.asClass().name().toString());
            }
        }
        if (!dateTimeFormatterProviderClassNames.isEmpty()) {
            reflectiveClass.produce(new ReflectiveClassBuildItem(true, false, false, false,
                    dateTimeFormatterProviderClassNames.toArray(new String[0])));
        }
    }

    @BuildStep
    @Record(value = ExecutionTime.STATIC_INIT, useIdentityComparisonForParameters = false)
    public void serverSerializers(ResteasyReactiveRecorder recorder,
            BeanContainerBuildItem beanContainerBuildItem,
            ApplicationResultBuildItem applicationResultBuildItem,
            List<MessageBodyReaderBuildItem> additionalMessageBodyReaders,
            List<MessageBodyWriterBuildItem> additionalMessageBodyWriters,
            List<MessageBodyReaderOverrideBuildItem> messageBodyReaderOverrideBuildItems,
            List<MessageBodyWriterOverrideBuildItem> messageBodyWriterOverrideBuildItems,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClass,
            BuildProducer<ServerSerialisersBuildItem> serverSerializersProducer) {

        ServerSerialisers serialisers = recorder.createServerSerialisers();
        SerializersUtil.setupSerializers(recorder, reflectiveClass, additionalMessageBodyReaders,
                additionalMessageBodyWriters, messageBodyReaderOverrideBuildItems, messageBodyWriterOverrideBuildItems,
                beanContainerBuildItem, applicationResultBuildItem, serialisers,
                RuntimeType.SERVER);

        // built-ins
        for (Serialisers.BuiltinWriter builtinWriter : ServerSerialisers.BUILTIN_WRITERS) {
            registerWriter(recorder, serialisers, builtinWriter.entityClass.getName(), builtinWriter.writerClass.getName(),
                    beanContainerBuildItem.getValue(),
                    builtinWriter.mediaType);
            reflectiveClass.produce(new ReflectiveClassBuildItem(true, false, false, builtinWriter.writerClass.getName()));
        }
        for (Serialisers.BuiltinReader builtinReader : ServerSerialisers.BUILTIN_READERS) {
            registerReader(recorder, serialisers, builtinReader.entityClass.getName(), builtinReader.readerClass.getName(),
                    beanContainerBuildItem.getValue(),
                    builtinReader.mediaType, builtinReader.constraint);
            reflectiveClass.produce(new ReflectiveClassBuildItem(true, false, false, builtinReader.readerClass.getName()));
        }

        serverSerializersProducer.produce(new ServerSerialisersBuildItem(serialisers));
    }

    @SuppressWarnings("unchecked")
    @BuildStep
    @Record(value = ExecutionTime.STATIC_INIT, useIdentityComparisonForParameters = false)
    public void setupDeployment(BeanArchiveIndexBuildItem beanArchiveIndexBuildItem,
            BeanContainerBuildItem beanContainerBuildItem,
            Capabilities capabilities,
            ResteasyReactiveConfig config,
            Optional<ResourceScanningResultBuildItem> resourceScanningResultBuildItem,
            ResteasyReactiveRecorder recorder,
            RecorderContext recorderContext,
            ShutdownContextBuildItem shutdownContext,
            HttpBuildTimeConfig vertxConfig,
            SetupEndpointsResultBuildItem setupEndpointsResult,
            ServerSerialisersBuildItem serverSerialisersBuildItem,
            List<DynamicFeatureBuildItem> dynamicFeatures,
            List<JaxrsFeatureBuildItem> features,
            Optional<RequestContextFactoryBuildItem> requestContextFactoryBuildItem,
            BuildProducer<ResteasyReactiveDeploymentInfoBuildItem> quarkusRestDeploymentInfoBuildItemBuildProducer,
            BuildProducer<ResteasyReactiveDeploymentBuildItem> quarkusRestDeploymentBuildItemBuildProducer,
            BuildProducer<ReflectiveClassBuildItem> reflectiveClass,
            BuildProducer<RouteBuildItem> routes,
            ApplicationResultBuildItem applicationResultBuildItem,
            ResourceInterceptorsBuildItem resourceInterceptorsBuildItem,
            ExceptionMappersBuildItem exceptionMappersBuildItem,
            ParamConverterProvidersBuildItem paramConverterProvidersBuildItem,
            ContextResolversBuildItem contextResolversBuildItem,
            ResteasyReactiveServerConfig serverConfig,
            LaunchModeBuildItem launchModeBuildItem)
            throws NoSuchMethodException {

        if (!resourceScanningResultBuildItem.isPresent()) {
            // no detected @Path, bail out
            return;
        }

        recorderContext.registerNonDefaultConstructor(
                MediaType.class.getDeclaredConstructor(String.class, String.class, String.class),
                mediaType -> Stream.of(mediaType.getType(), mediaType.getSubtype(), mediaType.getParameters())
                        .collect(toList()));

        IndexView index = beanArchiveIndexBuildItem.getIndex();

        ApplicationScanningResult appResult = applicationResultBuildItem.getResult();
        Set<String> singletonClasses = appResult.getSingletonClasses();
        Application application = appResult.getApplication();

        List<ResourceClass> resourceClasses = setupEndpointsResult.getResourceClasses();
        List<ResourceClass> subResourceClasses = setupEndpointsResult.getSubResourceClasses();
        AdditionalReaders additionalReaders = setupEndpointsResult.getAdditionalReaders();
        AdditionalWriters additionalWriters = setupEndpointsResult.getAdditionalWriters();

        ResourceInterceptors interceptors = resourceInterceptorsBuildItem.getResourceInterceptors();
        ExceptionMapping exceptionMapping = exceptionMappersBuildItem.getExceptionMapping();
        ContextResolvers contextResolvers = contextResolversBuildItem.getContextResolvers();
        ParamConverterProviders paramConverterProviders = paramConverterProvidersBuildItem.getParamConverterProviders();
        Function<String, BeanFactory<?>> factoryFunction = s -> FactoryUtils.factory(s, singletonClasses, recorder,
                beanContainerBuildItem);
        interceptors.initializeDefaultFactories(factoryFunction);
        exceptionMapping.initializeDefaultFactories(factoryFunction);
        contextResolvers.initializeDefaultFactories(factoryFunction);
        paramConverterProviders.initializeDefaultFactories(factoryFunction);
        paramConverterProviders.sort();
        interceptors.sort();
        interceptors.getContainerRequestFilters().validateThreadModel();

        Features feats = new Features();
        for (JaxrsFeatureBuildItem feature : features) {
            ResourceFeature resourceFeature = new ResourceFeature();
            resourceFeature
                    .setFactory(
                            FactoryUtils.factory(feature.getClassName(), singletonClasses, recorder,
                                    beanContainerBuildItem));
            feats.addFeature(resourceFeature);
        }

        DynamicFeatures dynamicFeats = new DynamicFeatures();
        for (DynamicFeatureBuildItem additionalDynamicFeature : dynamicFeatures) {
            ResourceDynamicFeature resourceFeature = new ResourceDynamicFeature();
            resourceFeature.setFactory(
                    recorder.factory(additionalDynamicFeature.getClassName(), beanContainerBuildItem.getValue()));
            dynamicFeats.addFeature(resourceFeature);
        }

        ServerSerialisers serialisers = serverSerialisersBuildItem.getSerialisers();

        for (AdditionalReaderWriter.Entry additionalReader : additionalReaders.get()) {
            String readerClass = additionalReader.getHandlerClass();
            registerReader(recorder, serialisers, additionalReader.getEntityClass(), readerClass,
                    beanContainerBuildItem.getValue(), additionalReader.getMediaType(), additionalReader.getConstraint());
            reflectiveClass.produce(new ReflectiveClassBuildItem(true, false, false, readerClass));
        }

        for (AdditionalReaderWriter.Entry entry : additionalWriters.get()) {
            String writerClass = entry.getHandlerClass();
            registerWriter(recorder, serialisers, entry.getEntityClass(), writerClass,
                    beanContainerBuildItem.getValue(), entry.getMediaType());
            reflectiveClass.produce(new ReflectiveClassBuildItem(true, false, false, writerClass));
        }

        BeanFactory<ResteasyReactiveInitialiser> initClassFactory = recorder.factory(QUARKUS_INIT_CLASS,
                beanContainerBuildItem.getValue());

        String applicationPath = determineApplicationPath(index, getAppPath(serverConfig.path));
        // spec allows the path contain encoded characters
        if ((applicationPath != null) && applicationPath.contains("%")) {
            applicationPath = Encode.decodePath(applicationPath);
        }

        String deploymentPath = sanitizeApplicationPath(applicationPath);

        // Handler used for both the default and non-default deployment path (specified as application path or resteasyConfig.path)
        // Routes use the order VertxHttpRecorder.DEFAULT_ROUTE_ORDER + 1 to ensure the default route is called before the resteasy one
        Class<? extends Application> applicationClass = application == null ? Application.class : application.getClass();
        DeploymentInfo deploymentInfo = new DeploymentInfo()
                .setInterceptors(interceptors.sort())
                .setConfig(createRestReactiveConfig(config))
                .setExceptionMapping(exceptionMapping)
                .setCtxResolvers(contextResolvers)
                .setFeatures(feats)
                .setClientProxyUnwrapper(new ClientProxyUnwrapper())
                .setApplicationSupplier(recorder.handleApplication(applicationClass, singletonClasses.isEmpty()))
                .setFactoryCreator(recorder.factoryCreator(beanContainerBuildItem.getValue()))
                .setDynamicFeatures(dynamicFeats)
                .setSerialisers(serialisers)
                .setApplicationPath(applicationPath)
                .setGlobalHandlerCustomizers(Collections.singletonList(new SecurityContextOverrideHandler.Customizer())) //TODO: should be pluggable
                .setResourceClasses(resourceClasses)
                .setDevelopmentMode(launchModeBuildItem.getLaunchMode() == LaunchMode.DEVELOPMENT)
                .setLocatableResourceClasses(subResourceClasses)
                .setParamConverterProviders(paramConverterProviders);
        quarkusRestDeploymentInfoBuildItemBuildProducer
                .produce(new ResteasyReactiveDeploymentInfoBuildItem(deploymentInfo));

        boolean servletPresent = false;
        int orderAdd = 1;
        if (capabilities.isPresent("io.quarkus.servlet")) {
            //if servlet is present we run RR before the default route
            //otherwise we run after it
            orderAdd = -1;
            servletPresent = true;
        }
        RuntimeValue<Deployment> deployment = recorder.createDeployment(deploymentInfo,
                beanContainerBuildItem.getValue(), shutdownContext, vertxConfig,
                requestContextFactoryBuildItem.map(RequestContextFactoryBuildItem::getFactory).orElse(null),
                initClassFactory, launchModeBuildItem.getLaunchMode(), servletPresent);

        quarkusRestDeploymentBuildItemBuildProducer
                .produce(new ResteasyReactiveDeploymentBuildItem(deployment, deploymentPath));

        if (!requestContextFactoryBuildItem.isPresent()) {
            Handler<RoutingContext> handler = recorder.handler(deployment);

            // Exact match for resources matched to the root path
            routes.produce(RouteBuildItem.builder()
                    .orderedRoute(deploymentPath, VertxHttpRecorder.DEFAULT_ROUTE_ORDER + orderAdd).handler(handler).build());
            String matchPath = deploymentPath;
            if (matchPath.endsWith("/")) {
                matchPath += "*";
            } else {
                matchPath += "/*";
            }
            // Match paths that begin with the deployment path
            routes.produce(
                    RouteBuildItem.builder().orderedRoute(matchPath, VertxHttpRecorder.DEFAULT_ROUTE_ORDER + orderAdd)
                            .handler(handler).build());
        }
    }

    private org.jboss.resteasy.reactive.common.ResteasyReactiveConfig createRestReactiveConfig(ResteasyReactiveConfig config) {
        Config mpConfig = ConfigProvider.getConfig();

        return new org.jboss.resteasy.reactive.common.ResteasyReactiveConfig(
                getEffectivePropertyValue("input-buffer-size", config.inputBufferSize.asLongValue(), Long.class, mpConfig),
                getEffectivePropertyValue("single-default-produces", config.singleDefaultProduces, Boolean.class, mpConfig),
                getEffectivePropertyValue("default-produces", config.defaultProduces, Boolean.class, mpConfig));
    }

    private <T> T getEffectivePropertyValue(String legacyPropertyName, T newPropertyValue, Class<T> propertyType,
            Config mpConfig) {
        Optional<T> legacyPropertyValue = mpConfig.getOptionalValue("quarkus.rest." + legacyPropertyName, propertyType);
        if (legacyPropertyValue.isPresent()) {
            return legacyPropertyValue.get();
        }
        return newPropertyValue;
    }

    @BuildStep
    @Record(ExecutionTime.RUNTIME_INIT)
    public void applyRuntimeConfig(ResteasyReactiveRuntimeRecorder recorder,
            Optional<ResteasyReactiveDeploymentBuildItem> deployment,
            ResteasyReactiveServerRuntimeConfig resteasyReactiveServerRuntimeConf) {
        if (!deployment.isPresent()) {
            return;
        }
        recorder.configure(deployment.get().getDeployment(), resteasyReactiveServerRuntimeConf);
    }

    @BuildStep
    public void securityExceptionMappers(BuildProducer<ExceptionMapperBuildItem> exceptionMapperBuildItemBuildProducer) {
        // built-ins
        exceptionMapperBuildItemBuildProducer.produce(new ExceptionMapperBuildItem(
                AuthenticationCompletionExceptionMapper.class.getName(),
                AuthenticationCompletionException.class.getName(),
                Priorities.USER, false));
        exceptionMapperBuildItemBuildProducer.produce(new ExceptionMapperBuildItem(
                AuthenticationFailedExceptionMapper.class.getName(),
                AuthenticationFailedException.class.getName(),
                Priorities.USER + 1, false));
        exceptionMapperBuildItemBuildProducer.produce(new ExceptionMapperBuildItem(
                AuthenticationRedirectExceptionMapper.class.getName(),
                AuthenticationRedirectException.class.getName(),
                Priorities.USER, false));
        exceptionMapperBuildItemBuildProducer.produce(new ExceptionMapperBuildItem(
                ForbiddenExceptionMapper.class.getName(),
                ForbiddenException.class.getName(),
                Priorities.USER + 1, false));
        exceptionMapperBuildItemBuildProducer.produce(new ExceptionMapperBuildItem(
                UnauthorizedExceptionMapper.class.getName(),
                UnauthorizedException.class.getName(),
                Priorities.USER + 1, false));
    }

    @BuildStep
    MethodScannerBuildItem integrateEagerSecurity(Capabilities capabilities, CombinedIndexBuildItem indexBuildItem) {
        if (!capabilities.isPresent(Capability.SECURITY)) {
            return null;
        }
        var index = indexBuildItem.getComputingIndex();
        return new MethodScannerBuildItem(new MethodScanner() {
            @Override
            public List<HandlerChainCustomizer> scan(MethodInfo method, ClassInfo actualEndpointClass,
                    Map<String, Object> methodContext) {
                return Objects.requireNonNullElse(
                        consumeStandardSecurityAnnotations(method, actualEndpointClass, index,
                                (c) -> Collections.singletonList(new EagerSecurityHandler.Customizer())),
                        Collections.emptyList());
            }
        });
    }

    private <T> T consumeStandardSecurityAnnotations(MethodInfo methodInfo, ClassInfo classInfo, IndexView index,
            Function<ClassInfo, T> function) {
        if (SecurityTransformerUtils.hasStandardSecurityAnnotation(methodInfo)) {
            return function.apply(methodInfo.declaringClass());
        }
        ClassInfo c = classInfo;
        while (c.superName() != null) {
            if (SecurityTransformerUtils.hasStandardSecurityAnnotation(c)) {
                return function.apply(c);
            }
            c = index.getClassByName(c.superName());
        }
        return null;
    }

    private Optional<String> getAppPath(Optional<String> newPropertyValue) {
        Optional<String> legacyProperty = ConfigProvider.getConfig().getOptionalValue("quarkus.rest.path", String.class);
        if (legacyProperty.isPresent()) {
            return legacyProperty;
        }

        return newPropertyValue;
    }

    private String determineApplicationPath(IndexView index, Optional<String> defaultPath) {
        Collection<AnnotationInstance> applicationPaths = index.getAnnotations(ResteasyReactiveDotNames.APPLICATION_PATH);
        if (applicationPaths.isEmpty()) {
            return defaultPath.orElse("/");
        }
        // currently we only examine the first class that is annotated with @ApplicationPath so best
        // fail if the user code has multiple such annotations instead of surprising the user
        // at runtime
        if (applicationPaths.size() > 1) {
            StringBuilder sb = new StringBuilder();
            boolean first = true;
            for (AnnotationInstance annotationInstance : applicationPaths) {
                if (first) {
                    first = false;
                } else {
                    sb.append(",");
                }
                sb.append(annotationInstance.target().asClass().name().toString());
            }
            throw new RuntimeException("Multiple classes ( " + sb.toString()
                    + ") have been annotated with @ApplicationPath which is currently not supported");
        }
        String applicationPath = null;
        AnnotationValue applicationPathValue = applicationPaths.iterator().next().value();
        if ((applicationPathValue != null)) {
            applicationPath = applicationPathValue.asString();
        }
        return applicationPath;
    }

    private String sanitizeApplicationPath(String applicationPath) {
        if ((applicationPath == null) || applicationPath.isEmpty() || "/".equals(applicationPath)) {
            return "/";
        }
        applicationPath = applicationPath.trim();
        if (applicationPath.equals("/"))
            applicationPath = "";
        // add leading slash
        if (!applicationPath.startsWith("/"))
            applicationPath = "/" + applicationPath;
        // remove trailing slash
        if (applicationPath.endsWith("/"))
            applicationPath = applicationPath.substring(0, applicationPath.length() - 1);
        return applicationPath;
    }

    private void registerWriter(ResteasyReactiveRecorder recorder, ServerSerialisers serialisers, String entityClass,
            String writerClass, BeanContainer beanContainer,
            String mediaType) {
        ResourceWriter writer = new ResourceWriter();
        writer.setFactory(recorder.factory(writerClass, beanContainer));
        writer.setMediaTypeStrings(Collections.singletonList(mediaType));
        recorder.registerWriter(serialisers, entityClass, writer);
    }

    private void registerReader(ResteasyReactiveRecorder recorder, ServerSerialisers serialisers, String entityClass,
            String readerClass, BeanContainer beanContainer, String mediaType,
            RuntimeType constraint) {
        ResourceReader reader = new ResourceReader();
        reader.setFactory(recorder.factory(readerClass, beanContainer));
        reader.setMediaTypeStrings(Collections.singletonList(mediaType));
        reader.setConstraint(constraint);
        recorder.registerReader(serialisers, entityClass, reader);
    }
}
