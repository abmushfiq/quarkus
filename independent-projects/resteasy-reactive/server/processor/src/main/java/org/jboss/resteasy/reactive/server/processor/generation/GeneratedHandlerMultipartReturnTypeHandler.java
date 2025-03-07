package org.jboss.resteasy.reactive.server.processor.generation;

import io.quarkus.gizmo.ClassOutput;
import java.util.HashMap;
import java.util.Map;
import javax.ws.rs.core.MediaType;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.IndexView;
import org.jboss.resteasy.reactive.common.processor.AdditionalWriters;
import org.jboss.resteasy.reactive.common.processor.EndpointIndexer;
import org.jboss.resteasy.reactive.server.core.multipart.MultipartMessageBodyWriter;

public class GeneratedHandlerMultipartReturnTypeHandler implements EndpointIndexer.MultipartReturnTypeHandler {
    private final Map<String, Boolean> multipartOutputGeneratedPopulators = new HashMap<>();

    final ClassOutput classOutput;

    public GeneratedHandlerMultipartReturnTypeHandler(ClassOutput classOutput) {
        this.classOutput = classOutput;
    }

    @Override
    public boolean handleMultipartForReturnType(AdditionalWriters additionalWriters, ClassInfo multipartClassInfo,
            IndexView index) {

        String className = multipartClassInfo.name().toString();
        Boolean canHandle = multipartOutputGeneratedPopulators.get(className);
        if (canHandle != null) {
            // we've already seen this class before and have done all we need
            return canHandle;
        }

        canHandle = false;
        if (FormDataOutputMapperGenerator.isReturnTypeCompatible(multipartClassInfo, index)) {
            additionalWriters.add(MultipartMessageBodyWriter.class.getName(), MediaType.MULTIPART_FORM_DATA, className);
            String mapperClassName = FormDataOutputMapperGenerator.generate(multipartClassInfo, classOutput, index);
            canHandle = true;
        }

        multipartOutputGeneratedPopulators.put(className, canHandle);
        return canHandle;

    }
}
