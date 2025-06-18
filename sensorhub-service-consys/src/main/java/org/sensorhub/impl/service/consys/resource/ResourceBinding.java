/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.resource;

import java.io.IOException;
import java.util.Collection;
import org.sensorhub.api.common.IdEncoders;
import org.sensorhub.impl.common.IdEncodersBase32;
import org.vast.util.Asserts;


/**
 * <p>
 * Base class for all resource formatter / parser
 * </p>
 * 
 * @param <K> Resource Key
 * @param <V> Resource Object
 *
 * @author Alex Robin
 * @since Jan 26, 2021
 */
public abstract class ResourceBinding<K, V>
{
    public static final String INDENT = "  ";
    
    
    protected final RequestContext ctx;
    protected final IdEncoders idEncoders;
    
    
    protected ResourceBinding(RequestContext ctx, IdEncoders idEncoders)
    {
        this.ctx = Asserts.checkNotNull(ctx, RequestContext.class);
        this.idEncoders = idEncoders != null ? idEncoders : new IdEncodersBase32();
    }
    
    
    public abstract V deserialize() throws IOException;
    public abstract void serialize(K key, V res, boolean showLinks) throws IOException;
    public abstract void startCollection() throws IOException;
    public abstract void endCollection(Collection<ResourceLink> links) throws IOException;
    
    
    protected String getAbsoluteHref(String href)
    {
        return href.startsWith("/") ? ctx.getApiRootURL() + href : href;
    }
}