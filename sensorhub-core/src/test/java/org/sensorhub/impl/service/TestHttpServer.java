/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2015 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service;

import static org.junit.Assert.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.URL;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.sensorhub.impl.SensorHub;
import org.sensorhub.impl.module.ModuleRegistry;
import org.sensorhub.impl.security.BasicSecurityRealmConfig;
import org.sensorhub.impl.security.BasicSecurityRealmConfig.UserConfig;
import org.sensorhub.impl.service.HttpServerConfig.AuthMethod;


public class TestHttpServer
{
    private static String USER_ID = "admin";
    private static String PASSWORD = "pwd";
    
    ModuleRegistry registry;
    
    
    @Before
    public void setup() throws Exception
    {
        System.out.println("\n*****************************");
        var hub = new SensorHub();
        hub.start();
        registry = hub.getModuleRegistry(); 
    }
    
    
    private HttpServer startServer(AuthMethod authMethod) throws Exception
    {
        HttpServerConfig config = new HttpServerConfig();
        config.autoStart = true;
        config.authMethod = authMethod;
        return (HttpServer)registry.loadModule(config);
    }
    
    
    private void addUsers() throws Exception
    {
        BasicSecurityRealmConfig securityConfig = new BasicSecurityRealmConfig();
        securityConfig.autoStart = true;
        UserConfig user = new UserConfig();
        user.userID = USER_ID;
        user.password = PASSWORD;
        securityConfig.users.add(user);
        registry.loadModule(securityConfig);
    }
    
    
    @Test
    public void testStartServer() throws Exception
    {
        startServer(null);
    }
    
    
    @Test
    public void testDeployServlet() throws Exception
    {
        var httpServer = startServer(null);
        final String testText = "Deploying hot servlet in SensorHub works";
        
        // deploy new servlet dynamically
        httpServer.deployServlet(new HttpServlet() {
            private static final long serialVersionUID = 1L;
            @Override
            protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws ServletException
            {
                try
                {
                    resp.getOutputStream().print(testText);
                    resp.getOutputStream().flush();
                }
                catch (IOException e)
                {
                    throw new ServletException(e);
                }
            }
        }, "/junit");
        
        // connect to servlet and check response
        URL url = new URL(httpServer.getServletsBaseUrl() + "junit");
        BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()));
        String resp = reader.readLine();
        System.out.println(resp);
        reader.close();
        
        assertTrue(resp.equals(testText));
    }
    
    
    private void testConnect(AuthMethod authMethod) throws Exception
    {
        addUsers();
        var httpServer = startServer(authMethod);
        
        // register simple authenticator
        if (authMethod != null)
        {
            //ClientAuth.createInstance("test");
            //ClientAuth.getInstance().setUser(USER_ID);
            //ClientAuth.getInstance().setPassword(PASSWORD.toCharArray());
            Authenticator.setDefault(new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                  PasswordAuthentication pa = new PasswordAuthentication (USER_ID, PASSWORD.toCharArray());
                  return pa;
              }
            });
        }
        
        // connect to servlet and check response
        URL url = new URL(httpServer.getServletsBaseUrl() + "test");
        BufferedReader reader = new BufferedReader(new InputStreamReader(url.openStream()));
        String resp = reader.readLine();
        System.out.println(resp);
        reader.close();
        
        assertTrue(resp.equals(HttpServer.TEST_MSG));
    }
    
    
    @Test
    public void testConnectNoAuth() throws Exception
    {
        testConnect(null);
    }
    
    
    @Test
    public void testConnectWithBasicAuth() throws Exception
    {
        testConnect(AuthMethod.BASIC);
    }
    
    
    @Test
    public void testConnectWithDigestAuth() throws Exception
    {
        testConnect(AuthMethod.DIGEST);
    }
    
    
    @After
    public void cleanup()
    {
        try
        {
            registry.shutdown(false, false);
        }
        catch (Exception e)
        {
            e.printStackTrace();
        }
    }
}
