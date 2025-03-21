/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2021 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.swe;

import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.sensorhub.utils.OshBundleActivator;
import org.vast.ows.sos.SOSUtils;


public class Activator extends OshBundleActivator implements BundleActivator
{

    @Override
    public void start(BundleContext context) throws Exception
    {
        super.start(context);
        
        // load OWS bindings
        // set the thread context classloader to the bundle classloader to
        // force ServiceLoader scan this bundle classpath
        var cl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(this.getClass().getClassLoader());
        SOSUtils.loadRegistry();
        Thread.currentThread().setContextClassLoader(cl);
    }
}
