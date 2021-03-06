/*
 * Copyright (c) 2005-2014, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.webapp.mgt;

import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.deployment.repository.util.DeploymentFileData;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.CarbonConstants;
import org.wso2.carbon.core.deployment.DeploymentSynchronizer;
import org.wso2.carbon.core.multitenancy.utils.TenantAxisUtils;
import org.wso2.carbon.tomcat.ext.utils.URLMappingHolder;
import org.wso2.carbon.tomcat.ext.valves.CarbonTomcatValve;
import org.wso2.carbon.tomcat.ext.valves.CompositeValve;
import org.wso2.carbon.utils.CarbonUtils;
import org.wso2.carbon.utils.deployment.GhostArtifactRegistry;
import org.wso2.carbon.utils.deployment.GhostDeployerUtils;
import org.wso2.carbon.utils.multitenancy.MultitenantUtils;
import org.wso2.carbon.webapp.mgt.utils.GhostWebappDeployerUtils;

import java.io.File;

/**
 * Handles management of webapps when ghost deployer is enabled. This includes deployment of
 * actual webapp from ghost form, dispatching requests to correct webapps, etc.
 */
public class GhostWebappDeployerValve extends CarbonTomcatValve {

    private static final Log log = LogFactory.getLog(GhostWebappDeployerValve.class);
    private boolean isGhostOn = GhostDeployerUtils.isGhostOn();

    @Override
    public void invoke(Request request,
                       Response response, CompositeValve compositeValve) {
        if (!isGhostOn) {
            getNext().invoke(request, response, compositeValve);
            return;
        }
        String requestURI = request.getRequestURI();

        if ((requestURI.startsWith(getWebContextRoot()+"carbon") && !requestURI.
                contains(WebappsConstants.WEBAPP_INFO_JSP_PAGE)) ||
            requestURI.contains("favicon.ico") || requestURI.contains("/fileupload/") ||
            requestURI.startsWith("/services")) {
            getNext().invoke(request, response, compositeValve);
            return;
        }
        //getting actual uri when accessing a virtual host through url mapping from the Map
        String requestedHostName = request.getServerName();
        String uriOfVirtualHost = URLMappingHolder.getInstance().getApplicationFromUrlMapping(requestedHostName);
        //getting the host name of first request from registry if & only if the request contains url-mapper suffix
        if (TomcatUtil.isVirtualHostRequest(requestedHostName) && uriOfVirtualHost == null) {
            uriOfVirtualHost = DataHolder.getHotUpdateService().
                    getApplicationContextForHost(requestedHostName);
        }
        if (uriOfVirtualHost != null) {
            requestURI = uriOfVirtualHost;
        }

        ConfigurationContext currentCtx;
        if (requestURI.contains("/t/")) {
            currentCtx = getCurrentConfigurationCtxFromURI(requestURI);
        } else {
            currentCtx = DataHolder.getServerConfigContext();
        }


        WebApplication deployedWebapp;
        //TODO: If webapp deployment takes time, then the immediate subsequent requests will fail after the after request
        //Since getDeployedWebappFromThisURI returns null just after the first request
        if ((deployedWebapp = getDeployedWebappFromThisURI(request.getContext().getPath(), currentCtx)) == null) {
            String ctxName = request.getContext().getPath();
            if (log.isDebugEnabled()) {
                log.debug("Looking for webapp in transit map with CtxName: " + ctxName);
            }
            WebApplication transitWebapp = GhostWebappDeployerUtils.
                    dispatchWebAppFromTransitGhosts(ctxName,
                                                    currentCtx);
            if (transitWebapp != null) {
                if (log.isDebugEnabled()) {
                    log.debug("Webapp found in transit map : " + ctxName);
                }
                // if the webapp is found in the temp ghost list, we have to wait until the
                // particular webapp is deployed or unloaded..
                String isBeingUnloaded = (String) transitWebapp.
                        getProperty(CarbonConstants.IS_ARTIFACT_BEING_UNLOADED);
                if (isBeingUnloaded != null && "true".equals(isBeingUnloaded)) {
                    // wait until the webapp is unloaded by the unload task
                    GhostWebappDeployerUtils.
                            waitForWebAppToLeaveTransit(transitWebapp.getContextName(),
                                                        currentCtx);
                    // now the webapp is unloaded and in ghost form so we can safely
                    // continue with invocation
                    handleWebapp(transitWebapp.getWebappFile().getName(), currentCtx);
                } else {
                    // wait until webapp is deployed
                    if (log.isDebugEnabled()) {
                        log.debug("Waiting till webapp leaves transit : " + ctxName);
                    }
                    GhostWebappDeployerUtils.
                            waitForWebAppToLeaveTransit(transitWebapp.getContextName(),
                                                        currentCtx);
                    try {
                        TomcatUtil.remapRequest(request);
                        return;
                    } catch (Exception e) {
                        log.error("Error when redirecting response to " + requestURI, e);
                    }
                }
            }
        } else {
            if (GhostWebappDeployerUtils.isGhostWebApp(deployedWebapp)) {
                handleWebapp(deployedWebapp.getWebappFile().getName(), currentCtx);
                try {
                    TomcatUtil.remapRequest(request);
                } catch (Exception e) {
                    log.error("Error when redirecting response to " + requestURI, e);
                }
            } else {
                // This means the webapp is being accessed in normal form so we have to update
                // the lase used time
                GhostWebappDeployerUtils.updateLastUsedTime(deployedWebapp);
            }
        }
        if (!requestURI.contains(WebappsConstants.WEBAPP_INFO_JSP_PAGE)) {
            getNext().invoke(request, response, compositeValve);
            return;
        }

        String webappFileName = request.getParameter("webappFileName");
        handleWebapp(webappFileName, currentCtx);
        getNext().invoke(request, response, compositeValve);
    }

    private WebApplication getDeployedWebappFromThisURI(String requestURI,
                                                        ConfigurationContext cfgCtx) {
        WebApplication deployedWebapp = null;
        WebApplicationsHolder webApplicationsHolder = getWebApplicationHolder(cfgCtx);
        for (WebApplication webApplication : webApplicationsHolder.getStartedWebapps().values()) {
            if (requestURI.equals(webApplication.getContextName())) {
                deployedWebapp = webApplication;
            }
        }
        return deployedWebapp;
    }

    private WebApplicationsHolder getWebApplicationHolder(ConfigurationContext cfgCtx) {
        WebApplicationsHolder webApplicationsHolder;
        webApplicationsHolder = (WebApplicationsHolder)
                cfgCtx.getProperty(CarbonConstants.WEB_APPLICATIONS_HOLDER);

        return webApplicationsHolder;
    }

    private void handleWebapp(String webappFileName, ConfigurationContext cfgCtx) {
        if (webappFileName != null) {
            WebApplication ghostWebapp;
            WebApplicationsHolder webApplicationsHolder = getWebApplicationHolder(cfgCtx);

            if (webApplicationsHolder != null) {
                ghostWebapp = webApplicationsHolder.getStartedWebapps().get(webappFileName);
                if (ghostWebapp != null) {
                    //TODO Handle the dep-synch update of webapps in workerNode
                    if (CarbonUtils.isWorkerNode() && GhostDeployerUtils.isPartialUpdateEnabled()) {
                        handleDepSynchUpdate(cfgCtx, ghostWebapp, webApplicationsHolder);
                    }
                    GhostWebappDeployerUtils.
                            deployActualWebApp(ghostWebapp, cfgCtx);
                }
            }
        }
    }

    private ConfigurationContext getCurrentConfigurationCtxFromURI(String uri) {
        return TenantAxisUtils.
                getTenantConfigurationContextFromUrl(uri, DataHolder.getServerConfigContext());
    }


    /**
     * Not needed anymore since we deprecate the partial depsync support
     *
     */
    @Deprecated
    private synchronized void handleDepSynchUpdate(ConfigurationContext configurationContext,
                                                   WebApplication webApplication,
                                                   WebApplicationsHolder webappsHolder) {
        String webappType = (String) webApplication.getProperty(WebappsConstants.WEBAPP_FILTER);
        String deploymentDir = WebappsConstants.WEBAPP_DEPLOYMENT_FOLDER;

        if (webappType.equals(WebappsConstants.JAGGERY_WEBAPP_FILTER_PROP)) {
            deploymentDir = WebappsConstants.JAGGERY_WEBAPP_REPO;
        } /*else if (webappType.equals(WebappsConstants.JAX_WEBAPP_FILTER_PROP)) {
            deploymentDir = WebappsConstants.JAX_WEBAPP_REPO;
        }*/
        String repoPath = configurationContext.getAxisConfiguration().getRepository().getPath();

        // this method should run only in tenant mode
        if (repoPath.contains(CarbonConstants.TENANTS_REPO) &&
            GhostWebappDeployerUtils.isGhostWebApp(webApplication)) {
            String fileName = (String) webApplication.getProperty(WebappsConstants.APP_FILE_NAME);
            if (fileName != null) {
                String filePath = repoPath + File.separator + deploymentDir + File.separator +
                                  fileName;
                File fileToUpdate = new File(filePath);

                if (!fileToUpdate.exists()) {
                    DeploymentSynchronizer depsync = DataHolder.getDeploymentSynchronizerService();
                    if (depsync != null && CarbonUtils.isDepSyncEnabled()) {
                        try {
                            // update webapp file
                            depsync.update(repoPath, filePath, 3);

                            if (fileToUpdate.exists()) {
                                DeploymentFileData dfd = new DeploymentFileData(fileToUpdate);
                                GhostArtifactRegistry ghostRegistry = GhostDeployerUtils.
                                        getGhostArtifactRegistry(configurationContext.
                                                getAxisConfiguration());
                                if (ghostRegistry != null &&
                                    ghostRegistry.getDeploymentFileData(filePath) == null) {
                                    File deployedWebappFile = new File(webApplication.
                                            getWebappFile().getName());
                                    if (webappsHolder.getStartedWebapps().
                                            containsKey(deployedWebappFile.getName())) {
                                        // remove the existing webapp
                                        webappsHolder.getStartedWebapps().
                                                remove(deployedWebappFile.getName());
                                    }
                                    webApplication.setWebappFile(dfd.getFile());
                                    webappsHolder.getStartedWebapps().
                                            put(dfd.getFile().getName(), webApplication);
                                }
                            }
                        } catch (Throwable t) {
                            log.error("Deployment synchronization update failed", t);
                        }
                    }
                }
            }
        }
    }

    private String getCtxNameFromRequestURI(String requestURI) {
        if (log.isDebugEnabled()) {
            log.debug("Request URI to retrieve CtxName : " + requestURI);
        }
        String ctxName = requestURI;
        if (requestURI.startsWith("/t/")) {
            String tenantDomain = MultitenantUtils.getTenantDomainFromUrl(requestURI);
            if (requestURI.contains(WebappsConstants.WEBAPP_PREFIX) ||
                requestURI.contains(WebappsConstants.JAX_WEBAPPS_PREFIX) ||
                requestURI.contains(WebappsConstants.JAGGERY_APPS_PREFIX)) {

                String subCtxName = ctxName.substring(ctxName.indexOf(tenantDomain) +
                                                      tenantDomain.length() + 1);
                if (subCtxName.contains("/")) {
                    subCtxName = subCtxName.substring(subCtxName.indexOf('/') + 1);
                }
                if (subCtxName.contains("/")) {
                    subCtxName = subCtxName.substring(subCtxName.indexOf('/'));
                    ctxName = requestURI.substring(0, requestURI.lastIndexOf(subCtxName));
                } else {
                    ctxName = requestURI;
                }
            }
        } else {
            ctxName = requestURI.substring(1);
            if (ctxName.contains("/")) {
                ctxName = "/".concat(ctxName.substring(0, ctxName.indexOf('/')));
            } else {
                ctxName = "/".concat(ctxName);
            }
        }
        return ctxName;
    }

    private static String getWebContextRoot() {
        String context = CarbonUtils.getServerConfiguration().getFirstProperty("WebContextRoot");
        if(!context.endsWith("/")) {
            return context + "/";
        }
        return context;
    }
}
