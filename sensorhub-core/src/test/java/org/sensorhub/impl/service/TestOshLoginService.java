/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.

******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.servlet.ServletRequest;
import org.eclipse.jetty.server.UserIdentity;
import org.junit.BeforeClass;
import org.junit.Test;
import org.sensorhub.api.security.IAuthorizer;
import org.sensorhub.api.security.IPermission;
import org.sensorhub.api.security.IPermissionPath;
import org.sensorhub.api.security.ISecurityManager;
import org.sensorhub.api.security.IUserInfo;
import org.sensorhub.api.security.IUserRegistry;
import org.eclipse.jetty.security.Authenticator;


/**
 * Unit tests for {@link OshLoginService}, focused on the login-type decision:
 * a certificate login (no password check) must be driven by a verified client
 * certificate on the request, never by a "CN=" prefix in a client-supplied
 * username. Otherwise a BASIC/DIGEST caller can bypass the password check.
 */
public class TestOshLoginService
{
    static final String CERT_ATTR = "javax.servlet.request.X509Certificate";

    // A self-signed certificate with subject CN=admin (parsed, not trusted here;
    // trust is enforced by the TLS layer, which is out of scope for this unit).
    static final String ADMIN_CERT_PEM = """
        -----BEGIN CERTIFICATE-----
        MIICnDCCAYQCCQDfmFJ9T+dEgzANBgkqhkiG9w0BAQsFADAQMQ4wDAYDVQQDDAVh
        ZG1pbjAeFw0yNjExMTEyMTUwNTNaFw00NjExMDYyMTUwNTNaMBAxDjAMBgNVBAMM
        BWFkbWluMIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEA1JaCLFpX6gtJ
        hV8LsAWwmAmXdi9zzPw554taeT2tlK6ZDwx2tKqyTw3w+0Yz5JtR2ssWJ7Q3Z0fA
        H0JkHk+huJAW2Q+aWLYUilgK9DR8RHpnK6JsJayoU44qHAb8gI7YOtG48POyEzHb
        Ovn65+i6hyqNPjFwAFLQJg45iW19yz2m1PK1/KyCvwBM1KXn+A4gx5W87tnXD4as
        rUbfKET718PQFwcGyk7gJ+o20WqlCsQeyipZuq+yqdBs/MrZXJc+UCWCFXF06zCB
        8O0JPOjOz6xEBCdvDpAwPw1YrXzFPz0CN28MlWSrkPY6kohNdvfVr+xRv05jWhMH
        y1gl13kLtwIDAQABMA0GCSqGSIb3DQEBCwUAA4IBAQBCd2Mm5oKz9jkUjDncxn+o
        UuxJ9JhZf1dl9r9f3OcPqXbpYd5oX4D1HPtKFsDNCpfDxXlD8dRiMN30q01OwTPJ
        4ZbOokiQezT6EpLpTkkr7sdO6AbXLn6ssoWuH7ryTZ6poKCyNDhQ6IdUSfxmYUju
        mDjbYldZfBJxb7DleWXqMaFtDrsFNN0Ent/GlhxdqWyquPzu94Pr9Taq/ka66/RI
        XyNFK47WIvcZ/v2HC+V/2oUNi2SQdSs1JIbd3BP5QEED725T3yPp7n52Km7HwFVd
        HI5VUN/IdDovS62/riFhHxSsIBOZYC0LBMk8lHIV+VrG7IGRZ1uOicMzTlIUJHDd
        -----END CERTIFICATE-----
        """;

    static X509Certificate adminCert;


    @BeforeClass
    public static void parseCert() throws Exception
    {
        var cf = CertificateFactory.getInstance("X.509");
        adminCert = (X509Certificate)cf.generateCertificate(
            new ByteArrayInputStream(ADMIN_CERT_PEM.getBytes(StandardCharsets.UTF_8)));
    }


    private OshLoginService newService(String userId, String password)
    {
        var mgr = new StubSecurityManager();
        if (userId != null)
            mgr.users.put(userId, new StubUser(userId, password));
        return new OshLoginService(mgr);
    }


    // A request that carries a verified client certificate chain.
    private ServletRequest requestWithCert(X509Certificate cert)
    {
        return (ServletRequest)Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class[] {ServletRequest.class},
            (proxy, method, args) -> {
                if ("getAttribute".equals(method.getName()) && args != null
                    && args.length == 1 && CERT_ATTR.equals(args[0]))
                    return new X509Certificate[] {cert};
                var rt = method.getReturnType();
                if (rt == boolean.class) return false;
                if (rt == int.class) return 0;
                if (rt == long.class) return 0L;
                return null;
            });
    }


    // ----- password (BASIC/DIGEST) path: request has no certificate -----

    @Test
    public void testPasswordLoginSucceeds()
    {
        var svc = newService("admin", "secret");
        assertNotNull("correct password should log in", svc.login("admin", "secret", null));
    }

    @Test
    public void testPasswordLoginWrongPasswordFails()
    {
        var svc = newService("admin", "secret");
        assertNull("wrong password must be rejected", svc.login("admin", "wrong", null));
    }

    @Test
    public void testCnUsernameDoesNotBypassPassword()
    {
        // The exploit: a BASIC/DIGEST client sends "CN=admin,x" to look like a
        // certificate DN. With no real certificate, this must NOT skip the check.
        var svc = newService("admin", "secret");
        assertNull("CN= username with wrong password must be rejected",
            svc.login("CN=admin,x", "anything", null));
    }

    @Test
    public void testCnUsernameWithoutCommaDoesNotCrash()
    {
        // Previously "CN=admin" (no comma) threw StringIndexOutOfBoundsException,
        // returning an unauthenticated 500. It must now be a clean rejection.
        var svc = newService("admin", "secret");
        assertNull(svc.login("CN=admin", "anything", null));
    }

    @Test
    public void testNullUsername()
    {
        var svc = newService("admin", "secret");
        assertNull(svc.login(null, "secret", null));
    }

    @Test
    public void testNullStoredPasswordRejected()
    {
        var svc = newService("admin", null);
        assertNull("user with no password must not log in", svc.login("admin", "", null));
    }


    // ----- certificate path: request carries a verified X509 certificate -----

    @Test
    public void testCertLoginSucceedsWithoutPassword()
    {
        var svc = newService("admin", "secret");
        var req = requestWithCert(adminCert);
        // Jetty passes the cert DN as the username for a cert login.
        UserIdentity id = svc.login("CN=admin", new X509Certificate[]{adminCert}, req);
        assertNotNull("verified client certificate should log in", id);
        assertEquals("admin", id.getUserPrincipal().getName());
    }

    @Test
    public void testCertLoginUsesCertSubjectNotUsername()
    {
        // Even if the passed username is garbage, the id comes from the cert CN.
        var svc = newService("admin", "secret");
        var req = requestWithCert(adminCert);
        UserIdentity id = svc.login("whatever", new X509Certificate[]{adminCert}, req);
        assertNotNull(id);
        assertEquals("admin", id.getUserPrincipal().getName());
    }

    @Test
    public void testCertLoginUnknownUserFails()
    {
        // Verified cert, but its CN maps to no registered user.
        var svc = newService(null, null); // empty registry
        var req = requestWithCert(adminCert);
        assertNull(svc.login("CN=admin", new X509Certificate[]{adminCert}, req));
    }


    // ----- lightweight stubs -----

    static class StubUser implements IUserInfo
    {
        final String id; final String pwd;
        StubUser(String id, String pwd) { this.id = id; this.pwd = pwd; }
        @Override public String getId() { return id; }
        @Override public String getPassword() { return pwd; }
        @Override public Collection<String> getRoles() { return List.of("admin"); }
        @Override public Map<String, Object> getAttributes() { return Collections.emptyMap(); }
        @Override public String getName() { return id; }
        @Override public String getDescription() { return null; }
        @Override public Collection<IPermissionPath> getAllowList() { return Collections.emptyList(); }
        @Override public Collection<IPermissionPath> getDenyList() { return Collections.emptyList(); }
    }

    static class StubSecurityManager implements ISecurityManager
    {
        final Map<String, IUserInfo> users = new java.util.HashMap<>();
        @Override public IUserInfo getUserInfo(String userID) { return users.get(userID); }
        @Override public boolean isAccessControlEnabled() { return true; }
        @Override public boolean isAuthorized(IUserInfo user, IPermissionPath request) { return true; }
        @Override public void registerAuthenticator(Authenticator a) { throw new UnsupportedOperationException(); }
        @Override public void unregisterAuthenticator(Authenticator a) { throw new UnsupportedOperationException(); }
        @Override public void registerUserRegistry(IUserRegistry r) { throw new UnsupportedOperationException(); }
        @Override public void unregisterUserRegistry(IUserRegistry r) { throw new UnsupportedOperationException(); }
        @Override public IUserRegistry getUserRegistry() { throw new UnsupportedOperationException(); }
        @Override public void registerAuthorizer(IAuthorizer a) { throw new UnsupportedOperationException(); }
        @Override public void unregisterAuthorizer(IAuthorizer a) { throw new UnsupportedOperationException(); }
        @Override public void registerModulePermissions(IPermission p) { throw new UnsupportedOperationException(); }
        @Override public void unregisterModulePermissions(IPermission p) { throw new UnsupportedOperationException(); }
        @Override public Authenticator getAuthenticator() { throw new UnsupportedOperationException(); }
        @Override public IPermission getModulePermissions(String moduleIdString) { throw new UnsupportedOperationException(); }
        @Override public Collection<IPermission> getAllModulePermissions() { throw new UnsupportedOperationException(); }
    }
}
