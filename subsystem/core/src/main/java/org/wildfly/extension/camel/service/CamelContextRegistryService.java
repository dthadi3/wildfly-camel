/*
 * #%L
 * Wildfly Camel :: Subsystem
 * %%
 * Copyright (C) 2013 - 2014 RedHat
 * %%
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * #L%
 */

package org.wildfly.extension.camel.service;

import static org.wildfly.extension.camel.CamelLogger.LOGGER;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.apache.camel.CamelContext;
import org.apache.camel.impl.event.CamelContextStartingEvent;
import org.apache.camel.impl.event.CamelContextStartupFailureEvent;
import org.apache.camel.impl.event.CamelContextStoppedEvent;
import org.apache.camel.spi.CamelContextTracker;
import org.apache.camel.spi.CamelEvent;
import org.apache.camel.spi.ManagementStrategy;
import org.apache.camel.support.EventNotifierSupport;
import org.jboss.modules.ModuleClassLoader;
import org.jboss.modules.ModuleIdentifier;
import org.jboss.msc.service.AbstractService;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.value.InjectedValue;
import org.wildfly.camel.utils.IllegalStateAssertion;
import org.wildfly.extension.camel.SpringCamelContextFactory;
import org.wildfly.extension.camel.CamelConstants;
import org.wildfly.extension.camel.CamelContextRegistry;
import org.wildfly.extension.camel.CamelSubsytemExtension;
import org.wildfly.extension.camel.ContextCreateHandler;
import org.wildfly.extension.camel.ContextCreateHandlerRegistry;
import org.wildfly.extension.camel.deployment.CamelDeploymentSettings;
import org.wildfly.extension.camel.handler.ModuleClassLoaderAssociationHandler;
import org.wildfly.extension.camel.parser.SubsystemState;
import org.wildfly.extension.camel.service.CamelContextRegistryService.MutableCamelContextRegistry;

/**
 * The {@link CamelContextRegistry} service
 *
 * @author Thomas.Diesler@jboss.com
 * @since 19-Apr-2013
 */
public class CamelContextRegistryService extends AbstractService<MutableCamelContextRegistry> {

    private static final String SPRING_BEANS_HEADER = "<beans xmlns='http://www.springframework.org/schema/beans' "
            + "xmlns:xsi='http://www.w3.org/2001/XMLSchema-instance' "
            + "xsi:schemaLocation='http://www.springframework.org/schema/beans http://www.springframework.org/schema/beans/spring-beans.xsd "
            + "http://www.springframework.org/schema/context http://www.springframework.org/schema/context/spring-context.xsd "
            + "http://camel.apache.org/schema/spring http://camel.apache.org/schema/spring/camel-spring.xsd'>";

    private final SubsystemState subsystemState;
    private final InjectedValue<ContextCreateHandlerRegistry> injectedHandlerRegistry = new InjectedValue<>();

    private MutableCamelContextRegistry contextRegistry;

    public static ServiceController<MutableCamelContextRegistry> addService(ServiceTarget serviceTarget, SubsystemState subsystemState) {
        CamelContextRegistryService service = new CamelContextRegistryService(subsystemState);
        ServiceBuilder<MutableCamelContextRegistry> builder = serviceTarget.addService(CamelConstants.CAMEL_CONTEXT_REGISTRY_SERVICE_NAME, service);
        builder.addDependency(CamelConstants.CONTEXT_CREATE_HANDLER_REGISTRY_SERVICE_NAME, ContextCreateHandlerRegistry.class, service.injectedHandlerRegistry);
        return builder.install();
    }

    public interface MutableCamelContextRegistry extends CamelContextRegistry {

        void addCamelContext(CamelContext camelctx);

        void removeCamelContext(CamelContext camelctx);
    }

    // Hide ctor
    private CamelContextRegistryService(SubsystemState subsystemState) {
        this.subsystemState = subsystemState;
    }

    @Override
    public void start(StartContext startContext) throws StartException {
        ContextCreateHandlerRegistry handlerRegistry = injectedHandlerRegistry.getValue();
        contextRegistry = new CamelContextRegistryImpl(handlerRegistry, startContext.getChildTarget());
        ((CamelContextTracker) contextRegistry).open();

        for (final String name : subsystemState.getContextDefinitionNames()) {
            createCamelContext(name, subsystemState.getContextDefinition(name));
        }
    }

    @Override
    public void stop(StopContext context) {
        for (final String name : subsystemState.getContextDefinitionNames()) {
            CamelContext camelctx = contextRegistry.getCamelContext(name);
            try {
                if (camelctx != null) {
                    camelctx.close();
                }
            } catch (Exception e) {
                LOGGER.warn("Cannot stop camel context: " + name, e);
            }
        }

        if (contextRegistry != null) {
            ((CamelContextTracker) contextRegistry).close();
        }
    }

    @Override
    public MutableCamelContextRegistry getValue() {
        return contextRegistry;
    }

    public void createCamelContext(String name, String contextDefinition) {
        ClassLoader classLoader = CamelContextRegistry.class.getClassLoader();
        ClassLoader tccl = SecurityActions.getContextClassLoader();
        try {
            SecurityActions.setContextClassLoader(classLoader);
            String beansXML = getBeansXML(name, contextDefinition);
            for (CamelContext camelctx : SpringCamelContextFactory.createCamelContextList(beansXML.getBytes(), classLoader)) {
                camelctx.start();
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Cannot create camel context: " + name, ex);
        } finally {
            SecurityActions.setContextClassLoader(tccl);
        }
    }

    private String getBeansXML(String name, String contextDefinition) {
        String hashReplaced = contextDefinition.replace("#{", "${");
        return SPRING_BEANS_HEADER + "<camelContext id='" + name + "' xmlns='http://camel.apache.org/schema/spring'>" + hashReplaced + "</camelContext></beans>";
    }

    final class CamelContextRegistryImpl extends CamelContextTracker implements MutableCamelContextRegistry {

        private final Set<CamelContext> contexts = new LinkedHashSet<>();
        private final ContextCreateHandlerRegistry handlerRegistry;
        private final ServiceTarget serviceTarget;

        CamelContextRegistryImpl(ContextCreateHandlerRegistry handlerRegistry, ServiceTarget serviceTarget) {
            this.handlerRegistry = handlerRegistry;
            this.serviceTarget = serviceTarget;
        }

        @Override
        public List<String> getCamelContextNames() {
            List<String> result = contexts.stream()
                    .map(ctx -> ctx.getName())
                    .collect(Collectors.toList());
            return result;
        }

        @Override
        public CamelContext getCamelContext(String name) {
            CamelContext result = null;
            synchronized (contexts) {
                for (CamelContext camelctx : contexts) {
                    if (camelctx.getName().equals(name)) {
                        result = camelctx;
                        break;
                    }
                }
            }
            return result;
        }

        @Override
        public Set<CamelContext> getCamelContexts() {
            synchronized (contexts) {
                return Collections.unmodifiableSet(contexts);
            }
        }

        @Override
        public void contextCreated(CamelContext camelctx) {

            boolean enableIntegration = true;

            // Enable the integration based on deployment settings
            ModuleClassLoader moduleClassLoader = ModuleClassLoaderAssociationHandler.getModuleClassLoader(camelctx);
            ModuleIdentifier moduleId = moduleClassLoader.getModule().getIdentifier();
            if (moduleId.getName().startsWith("deployment.")) {
                String depName = moduleId.getName().substring(11);
                CamelDeploymentSettings depSettings = CamelDeploymentSettings.get(depName);
                enableIntegration = depSettings.isEnabled();
            }

            if (enableIntegration) {

                // Call the default {@link ContextCreateHandler}s
                for (ContextCreateHandler handler : handlerRegistry.getContextCreateHandlers(null)) {
                    handler.setup(camelctx);
                }

                // Verify that the application context class loader is a ModuleClassLoader
                ClassLoader classLoader = camelctx.getApplicationContextClassLoader();
                IllegalStateAssertion.assertTrue(classLoader instanceof ModuleClassLoader, "Invalid class loader association: " + classLoader);

                // Call the module specific {@link ContextCreateHandler}s
                for (ContextCreateHandler handler : handlerRegistry.getContextCreateHandlers(classLoader)) {
                    handler.setup(camelctx);
                }

                ManagementStrategy mgmtStrategy = camelctx.getManagementStrategy();
                mgmtStrategy.addEventNotifier(new EventNotifierSupport() {

					@Override
					public void notify(CamelEvent event) throws Exception {

                        // Starting
                        if (event instanceof CamelContextStartingEvent) {
                            CamelContextStartingEvent camelevt = (CamelContextStartingEvent) event;
                            CamelContext camelctx = camelevt.getContext();
                            addCamelContext(camelctx);
                            LOGGER.info("Camel context starting: {}", camelctx.getName());
                        }

                        // Start failure
                        else if (event instanceof CamelContextStartupFailureEvent) {
                        	CamelContextStartupFailureEvent camelevt = (CamelContextStartupFailureEvent) event;
                            CamelContext camelctx = camelevt.getContext();
                            removeCamelContext(camelctx);
                            LOGGER.info("Camel context failure: {}", camelctx.getName());
                        }

                        // Stopped
                        else if (event instanceof CamelContextStoppedEvent) {
                            CamelContextStoppedEvent camelevt = (CamelContextStoppedEvent) event;
                            CamelContext camelctx = camelevt.getContext();
                            removeCamelContext(camelctx);
                            LOGGER.info("Camel context stopped: {}", camelctx.getName());
                        }
					}
                });
            }
        }

        @Override
        public void addCamelContext(CamelContext camelctx) {
            synchronized (contexts) {
                contexts.add(camelctx);
                subsystemState.processExtensions(new Consumer<CamelSubsytemExtension>() {
                    @Override
                    public void accept(CamelSubsytemExtension plugin) {
                        plugin.addCamelContext(serviceTarget, camelctx);
                    }
                });
            }
        }

        @Override
        public void removeCamelContext(CamelContext camelctx) {
            synchronized (contexts) {
                subsystemState.processExtensions(new Consumer<CamelSubsytemExtension>() {
                    @Override
                    public void accept(CamelSubsytemExtension plugin) {
                        plugin.removeCamelContext(camelctx);
                    }
                });
                contexts.remove(camelctx);
            }
        }
    }
}
