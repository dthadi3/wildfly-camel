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

package org.wildfly.extension.camel.undertow;

import static org.wildfly.extension.camel.CamelLogger.LOGGER;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import org.apache.camel.component.undertow.HttpHandlerRegistrationInfo;
import org.apache.camel.component.undertow.UndertowConsumer;
import org.apache.camel.component.undertow.UndertowHost;
import org.apache.camel.component.undertow.handlers.CamelWebSocketHandler;
import org.jboss.as.network.NetworkUtils;
import org.jboss.as.network.SocketBinding;
import org.jboss.as.server.CurrentServiceContainer;
import org.jboss.modules.ModuleClassLoader;
import org.jboss.msc.service.AbstractService;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StartContext;
import org.jboss.msc.service.StartException;
import org.jboss.msc.service.StopContext;
import org.jboss.msc.value.InjectedValue;
import org.wildfly.camel.utils.IllegalStateAssertion;
import org.wildfly.extension.camel.CamelConstants;
import org.wildfly.extension.camel.parser.SubsystemState.RuntimeState;
import org.wildfly.extension.camel.service.CamelEndpointDeployerService.CamelEndpointDeployerHandler;
import org.wildfly.extension.camel.service.CamelEndpointDeploymentSchedulerService;
import org.wildfly.extension.undertow.Host;
import org.wildfly.extension.undertow.UndertowEventListener;
import org.wildfly.extension.undertow.UndertowListener;
import org.wildfly.extension.undertow.UndertowService;

import io.undertow.Handlers;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.RoutingHandler;
import io.undertow.servlet.api.Deployment;
import io.undertow.servlet.api.DeploymentInfo;
import io.undertow.util.Methods;
import io.undertow.util.PathTemplate;
import io.undertow.util.URLUtils;

/**
 * The {@link UndertowHost} service
 *
 * @author Thomas.Diesler@jboss.com
 * @since 19-Apr-2013
 */
public class CamelUndertowHostService extends AbstractService<UndertowHost> {

    static final ServiceName SERVICE_NAME = CamelConstants.CAMEL_BASE_NAME.append("Undertow");

    private final InjectedValue<SocketBinding> injectedHttpSocketBinding = new InjectedValue<>();
    private final InjectedValue<UndertowService> injectedUndertowService = new InjectedValue<>();
    private final InjectedValue<Host> injectedDefaultHost = new InjectedValue<>();

    private final RuntimeState runtimeState;
    private UndertowEventListener eventListener;
    private UndertowHost undertowHost;

    @SuppressWarnings("deprecation")
    public static ServiceController<UndertowHost> addService(ServiceTarget serviceTarget, RuntimeState runtimeState) {
        CamelUndertowHostService service = new CamelUndertowHostService(runtimeState);
        ServiceBuilder<UndertowHost> builder = serviceTarget.addService(SERVICE_NAME, service);
        builder.addDependency(UndertowService.UNDERTOW, UndertowService.class, service.injectedUndertowService);
        builder.addDependency(SocketBinding.JBOSS_BINDING_NAME.append("http"), SocketBinding.class, service.injectedHttpSocketBinding);
        builder.addDependency(UndertowService.virtualHostName("default-server", "default-host"), Host.class, service.injectedDefaultHost);
        return builder.install();
    }

    // Hide ctor
    private CamelUndertowHostService(RuntimeState runtimeState) {
        this.runtimeState = runtimeState;
    }

    @Override
    public void start(StartContext startContext) throws StartException {
        runtimeState.setHttpHost(getConnectionURL());
        eventListener = new CamelUndertowEventListener();
        injectedUndertowService.getValue().registerListener(eventListener);
        undertowHost = new WildFlyUndertowHost(injectedDefaultHost.getValue());
    }

    private URL getConnectionURL() throws StartException {

        SocketBinding socketBinding = injectedHttpSocketBinding.getValue();
        InetAddress address = socketBinding.getNetworkInterfaceBinding().getAddress();

        URL result;
        try {
            String hostAddress = NetworkUtils.formatPossibleIpv6Address(address.getHostAddress());
            result = new URL(socketBinding.getName() + "://" + hostAddress + ":" + socketBinding.getPort());
        } catch (MalformedURLException ex) {
            throw new StartException(ex);
        }
        return result;
    }

    @Override
    public void stop(StopContext context) {
        injectedUndertowService.getValue().unregisterListener(eventListener);
    }

    @Override
    public UndertowHost getValue() throws IllegalStateException {
        return undertowHost;
    }

    static class WildFlyUndertowHost implements UndertowHost {
        private static final String REST_PATH_PLACEHOLDER = "{";
        private static final String DEFAULT_METHODS = "GET,HEAD,POST,PUT,DELETE,TRACE,OPTIONS,CONNECT,PATCH";
        private final Map<String, DelegatingRoutingHandler> handlers = new ConcurrentHashMap<>();
        private final Host defaultHost;

        WildFlyUndertowHost(Host host) {
            this.defaultHost = host;
        }

        @Override
        public void validateEndpointURI(URI httpURI) {
            validateEndpointPort(httpURI);
            validateEndpointContextPath(httpURI);
        }

        private void validateEndpointPort(URI httpURI) {
            // Camel HTTP endpoint port defaults are 0 or -1
            boolean portMatched = httpURI.getPort() == 0 || httpURI.getPort() == -1;

            // If a port was specified, verify that undertow has a listener configured for it
            if (!portMatched) {
                for (UndertowListener listener : defaultHost.getServer().getListeners()) {
                    SocketBinding binding = listener.getSocketBinding();
                    if (binding != null) {
                        if (binding.getPort() == httpURI.getPort()) {
                            portMatched = true;
                            break;
                        }
                    }
                }
            }

            if (!"localhost".equals(httpURI.getHost())) {
                LOGGER.debug("Cannot bind to host other than 'localhost': {}", httpURI);
            }
            if (!portMatched) {
                LOGGER.debug("Cannot bind to specific port: {}", httpURI);
            }
        }

        private void validateEndpointContextPath(URI httpURI) {
            String undertowEndpointPath = getContextPath(httpURI);
            Set<Deployment> deployments = defaultHost.getDeployments();
            for (Deployment deployment : deployments) {
                DeploymentInfo depInfo = deployment.getDeploymentInfo();
                String contextPath = depInfo.getContextPath();
                if (contextPath.equals(undertowEndpointPath)) {
                    final HttpHandler handler = deployment.getHandler();
                    if (handler instanceof CamelEndpointDeployerHandler && ((CamelEndpointDeployerHandler)handler).getRoutingHandler() instanceof DelegatingRoutingHandler) {
                        final ModuleClassLoader oldCl = ((DelegatingRoutingHandler)((CamelEndpointDeployerHandler)handler).getRoutingHandler()).classLoader;
                        final ModuleClassLoader tccl = checkTccl();
                        if (tccl != oldCl) {
                            // Avoid allowing handlers from distinct apps to handle the same path
                            throw new IllegalStateException("Cannot add "+ HttpHandler.class.getName() +" for path " + contextPath + " defined in " + tccl.getName() + " because that path is already served by "+ oldCl.getName());
                        }
                    } else {
                        // Another application already serves this path
                        throw new IllegalStateException("Cannot overwrite context path " + contextPath + " owned by " + depInfo.getDeploymentName());
                    }
                }
            }
        }

        @Override
        public HttpHandler registerHandler(UndertowConsumer consumer, HttpHandlerRegistrationInfo reginfo, HttpHandler handler) {
            boolean matchOnUriPrefix = reginfo.isMatchOnUriPrefix();
            URI httpURI = reginfo.getUri();

            String contextPath = getContextPath(httpURI);
            LOGGER.debug("Using context path {}", contextPath);

            String relativePath = getRelativePath(httpURI, matchOnUriPrefix);
            LOGGER.debug("Using relative path {}", relativePath);

            boolean registerRoutingHandler = false;
            DelegatingRoutingHandler routingHandler = handlers.get(contextPath);
            if (routingHandler == null) {
                routingHandler = new DelegatingRoutingHandler(checkTccl());
                registerRoutingHandler = true;
                LOGGER.debug("Created new DelegatingRoutingHandler {}", routingHandler);
            }

            String methods = reginfo.getMethodRestrict() == null ? DEFAULT_METHODS : reginfo.getMethodRestrict();
            LOGGER.debug("Using methods {}", methods);

            HttpHandler result = null;
            for (String method : methods.split(",")) {
                LOGGER.debug("Adding {}: {} for handler {}", method, relativePath, handler);
                result = routingHandler.add(method, relativePath, handler);
            }

            if (registerRoutingHandler) {
                lookupDeploymentSchedulerService(routingHandler.classLoader).schedule(httpURI.resolve(contextPath), routingHandler);
                handlers.put(contextPath, routingHandler);
            }

            return result;
        }

        @Override
        public void unregisterHandler(UndertowConsumer consumer, HttpHandlerRegistrationInfo reginfo) {
            boolean matchOnUriPrefix = reginfo.isMatchOnUriPrefix();
            URI httpURI = reginfo.getUri();
            String contextPath = getContextPath(httpURI);
            LOGGER.debug("unregisterHandler {}", contextPath);

            DelegatingRoutingHandler routingHandler = handlers.get(contextPath);
            if (routingHandler != null) {
                String methods = reginfo.getMethodRestrict() == null ? DEFAULT_METHODS : reginfo.getMethodRestrict();
                boolean routingHandlerEmpty = false;
                for (String method : methods.split(",")) {
                    String relativePath = getRelativePath(httpURI, matchOnUriPrefix);
                    routingHandlerEmpty = routingHandler.remove(method, relativePath);
                    LOGGER.debug("Unregistered {}: {}", method, relativePath);
                }

                // No paths remain registered so remove the base handler
                if (routingHandlerEmpty) {
                    lookupDeploymentSchedulerService(routingHandler.classLoader).unschedule(httpURI.resolve(contextPath));
                    handlers.remove(contextPath);
                    LOGGER.debug("Unregistered root handler from {}", contextPath);
                }
            }
        }

        private String getBasePath(URI httpURI) {
            String path = httpURI.getPath();
            if (path.contains(REST_PATH_PLACEHOLDER)) {
                path = PathTemplate.create(path).getBase();
            }
            return URLUtils.normalizeSlashes(path);
        }

        private String getContextPath(URI httpURI) {
            String path = getBasePath(httpURI);
            String[] pathElements = path.replaceFirst("^/", "").split("/");
            if (pathElements.length > 1) {
                return String.format("/%s/%s", pathElements[0], pathElements[1]);
            }
            return String.format("/%s", pathElements[0]);
        }

        private String getRelativePath(URI httpURI, boolean matchOnUriPrefix) {
            String path = httpURI.getPath();
            String contextPath = getContextPath(httpURI);
            String normalizedPath = URLUtils.normalizeSlashes(path.substring(contextPath.length()));
            if (matchOnUriPrefix) {
                normalizedPath += "*";
            }
            return normalizedPath;
        }

        private static CamelEndpointDeploymentSchedulerService lookupDeploymentSchedulerService(ClassLoader classLoader) {
            final ServiceName serviceName = CamelEndpointDeploymentSchedulerService
                .deploymentSchedulerServiceName(classLoader);
            ServiceController<?> serviceController = CurrentServiceContainer.getServiceContainer()
                .getRequiredService(serviceName);
            return (CamelEndpointDeploymentSchedulerService) serviceController.getValue();
        }

        private static ModuleClassLoader checkTccl() {
            final ClassLoader tccl = Thread.currentThread().getContextClassLoader();
            if (tccl instanceof ModuleClassLoader && ((ModuleClassLoader) tccl).getName().startsWith("deployment.")) {
                return (ModuleClassLoader) tccl;
            } else {
                throw new IllegalStateException("Expected an org.jboss.modules.ModuleClassLoader with name starting with 'deployment.'; found "
                    + tccl);
            }
        }
    }

    static class DelegatingRoutingHandler implements HttpHandler {

        private final Map<MethodPathKey, MethodPathValue> paths = new ConcurrentHashMap<>();
        private final RoutingHandler delegate = Handlers.routing();
        /** The class loader of the deployment in which the path served by this {@link DelegatingRoutingHandler} was defined */
        private final ModuleClassLoader classLoader;

        public DelegatingRoutingHandler(ModuleClassLoader classLoader) {
            this.classLoader = classLoader;
        }

        HttpHandler add(String method, String path, HttpHandler handler) {
            MethodPathKey key = new MethodPathKey(method, path);
            HttpHandler result = null;
            synchronized (paths) {
                MethodPathValue value = paths.computeIfAbsent(key, k -> new MethodPathValue());
                result = value.addRef(handler, method, path);
            }

            if (handler == result) {
                // register only the very first handler per path and method
                LOGGER.debug("Registered paths {}", this.toString());
                delegate.add(method, path, handler);
            }
            return result;
        }

        boolean remove(String method, String path) {
            MethodPathKey key = new MethodPathKey(method, path);
            boolean result;
            synchronized (paths) {
                MethodPathValue value = paths.get(key);
                if (value != null) {
                    value.removeRef();
                    if (value.refCount <= 0) {
                        paths.remove(key);
                    }
                }
                result = paths.isEmpty();
            }
            delegate.remove(Methods.fromString(method), path);
            return result;
        }

        @Override
        public void handleRequest(HttpServerExchange exchange) throws Exception {
            if (exchange.getRelativePath().isEmpty()) {
                exchange.setRelativePath("/");
            }
            delegate.handleRequest(exchange);
        }

        @Override
        public String toString() {
            String formattedPaths = paths.entrySet()
                .stream()
                .map(entry -> entry.toString())
                .collect(Collectors.joining(", "));
            return String.format("DelegatingRoutingHandler [%s]", formattedPaths);
        }
    }

    static class MethodPathKey {
        private final String method;
        private final String path;

        private MethodPathKey(String method, String path) {
            this.method = method;
            this.path = path;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }

            MethodPathKey that = (MethodPathKey) o;

            if (method != null ? !method.equals(that.method) : that.method != null) {
                return false;
            }
            return path != null ? path.equals(that.path) : that.path == null;
        }

        @Override
        public int hashCode() {
            int result = method != null ? method.hashCode() : 0;
            result = 31 * result + (path != null ? path.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            return String.format("%s: %s", method, path);
        }
    }

    static class MethodPathValue {
        private int refCount;
        private HttpHandler handler;

        MethodPathValue() {
        }

        public HttpHandler addRef(HttpHandler handler, String method, String path) {
            if (this.handler == null) {
                this.handler = handler;
                refCount++;
                return handler;
            } else if ("OPTIONS".equals(method) || CamelWebSocketHandler.class == this.handler.getClass()
                    && CamelWebSocketHandler.class == handler.getClass()) {
                refCount++;
                return this.handler;
            } else {
                throw new IllegalStateException(
                        String.format("Duplicate handler for method %s and path '%s': '%s', '%s'", method, path,
                                this.handler, handler));
            }
        }

        public void removeRef() {
            if (--refCount == 0) {
                this.handler = null;
            }
        }

        @Override
        public String toString() {
            return handler == null ? "null" : handler.toString();
        }

    }

    class CamelUndertowEventListener implements UndertowEventListener {

        private final ConcurrentMap<String, Boolean> existingContextPaths = new ConcurrentHashMap<>();

        @Override
        public void onDeploymentStart(Deployment dep, Host host) {
            // Ensure that a deployment HttpHandler cannot overwrite handlers created by camel-undertow
            checkForOverlappingContextPath(dep);

            runtimeState.addHttpContext(dep.getServletContext().getContextPath());
        }

        @Override
        public void onDeploymentStop(Deployment dep, Host host) {
            runtimeState.removeHttpContext(dep.getServletContext().getContextPath());
            final DeploymentInfo depInfo = dep.getDeploymentInfo();
            if (dep.getHandler() != null) {
                final String contextPath = depInfo.getContextPath();
                existingContextPaths.remove(contextPath);
            }
        }

        private void checkForOverlappingContextPath(Deployment dep) {
            final DeploymentInfo depInfo = dep.getDeploymentInfo();
            if (dep.getHandler() != null) {
                final String contextPath = depInfo.getContextPath();
                final Boolean exists = existingContextPaths.putIfAbsent(contextPath, Boolean.TRUE);
                IllegalStateAssertion.assertFalse(Boolean.TRUE == exists,
                        "Cannot overwrite context path " + contextPath + " owned by camel-undertow" + contextPath);
            }
        }
    }
}
