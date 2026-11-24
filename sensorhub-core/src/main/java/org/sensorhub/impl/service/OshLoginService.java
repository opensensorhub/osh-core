/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2016 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service;

import java.security.Principal;
import java.security.cert.X509Certificate;
import javax.naming.ldap.LdapName;
import javax.security.auth.Subject;
import javax.servlet.ServletRequest;
import org.eclipse.jetty.security.DefaultIdentityService;
import org.eclipse.jetty.security.IdentityService;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.security.Credential;
import org.sensorhub.api.security.ISecurityManager;
import org.sensorhub.api.security.IUserInfo;


public class OshLoginService implements LoginService
{
    final ISecurityManager securityManager;
    IdentityService identityService = new DefaultIdentityService();
    
    
    public static class UserPrincipal implements Principal
    {
        private final IUserInfo user;
        
        public UserPrincipal(IUserInfo user)
        {
            this.user = user;
        }
        
        @Override
        public String getName()
        {
            return user.getId();
        }
        
        @Override
        public String toString()
        {
            return getName();
        }
    }
    
    
    public static class RolePrincipal implements Principal
    {
        private final String _roleName;
        
        public RolePrincipal(String name)
        {
            _roleName=name;
        }
        
        @Override
        public String getName()
        {
            return _roleName;
        }
    }
    
    
    public OshLoginService(ISecurityManager securityManager)
    {
        this.securityManager = securityManager;
    }
    
    
    @Override
    public IdentityService getIdentityService()
    {
        return identityService;
    }


    @Override
    public String getName()
    {
        return "OpenSensorHub: Authentication Required";
    }


    @Override
    public UserIdentity login(String username, Object credentials, ServletRequest request)
    {
        if (username == null)
            return null;

        // The login type is determined by the verified X509 client certificate
        // that the TLS layer attached to the request, never by the shape of the
        // username. With BASIC/DIGEST the username is supplied by the client, so
        // inferring a certificate login from a "CN=" prefix let a caller skip
        // the password check and log in as any user (authentication bypass).
        X509Certificate[] certs = (request != null) ?
            (X509Certificate[])request.getAttribute("javax.servlet.request.X509Certificate") : null;
        boolean isCert = (certs != null && certs.length > 0);

        // For a certificate login, take the user id from the verified certificate
        // itself, not from the passed-in username.
        String userId;
        if (isCert)
        {
            userId = getSubjectCommonName(certs[0]);
            if (userId == null)
                return null;
        }
        else
            userId = username;

        IUserInfo user = securityManager.getUserInfo(userId);
        if (user == null)
            return null;

        // A certificate login is already proven by the TLS handshake. A password
        // login must always have its credentials verified; it is never skipped.
        if (isCert)
            return createUserIdentity(user, credentials);

        String storedPassword = user.getPassword();
        if (storedPassword == null)
            return null;
        Credential storedCredential = Credential.getCredential(storedPassword);
        if (storedCredential.check(credentials))
            return createUserIdentity(user, credentials);

        return null;
    }


    /*
     * Extracts the CN attribute from a verified certificate's subject DN,
     * parsed as a proper LDAP name (CN need not be first, values may be escaped).
     */
    protected static String getSubjectCommonName(X509Certificate cert)
    {
        try
        {
            var dn = cert.getSubjectX500Principal().getName();
            for (var rdn: new LdapName(dn).getRdns())
            {
                if ("CN".equalsIgnoreCase(rdn.getType()))
                    return rdn.getValue().toString();
            }
        }
        catch (Exception e)
        {
            // malformed DN: treat as no usable identity
        }
        return null;
    }
    
    
    protected UserIdentity createUserIdentity(final IUserInfo user, Object credential)
    {
        Principal principal = new UserPrincipal(user);
        Subject subject = new Subject();
        subject.getPrincipals().add(principal);
        subject.getPrivateCredentials().add(credential);
        subject.setReadOnly();
        
        String[] roles = user.getRoles().toArray(new String[0]);
        UserIdentity identity = identityService.newUserIdentity(subject, principal, roles);
        return identity;
    }


    @Override
    public void logout(UserIdentity user)
    {
    }


    @Override
    public void setIdentityService(IdentityService identityService)
    {
        this.identityService = identityService;
    }


    @Override
    public boolean validate(UserIdentity user)
    {
        return true;
    }

}
