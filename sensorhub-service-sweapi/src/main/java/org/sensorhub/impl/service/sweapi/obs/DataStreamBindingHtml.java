/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2020 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.sweapi.obs;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.sensorhub.api.common.IdEncoders;
import org.sensorhub.api.data.IDataStreamInfo;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.obs.DataStreamKey;
import org.sensorhub.impl.service.sweapi.SWECommonUtils;
import org.sensorhub.impl.service.sweapi.resource.RequestContext;
import org.sensorhub.impl.service.sweapi.resource.ResourceBindingHtml;
import org.sensorhub.impl.service.sweapi.resource.ResourceFormat;
import org.sensorhub.impl.service.sweapi.system.SystemHandler;
import org.vast.util.Asserts;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import static j2html.TagCreator.*;


/**
 * <p>
 * HTML formatter for datastream resources
 * </p>
 *
 * @author Alex Robin
 * @since March 31, 2022
 */
public class DataStreamBindingHtml extends ResourceBindingHtml<DataStreamKey, IDataStreamInfo>
{
    final Map<String, CustomObsFormat> customFormats;
    final ResourceFormat obsFormat;
    final boolean isSummary;
    final String collectionTitle;
    
    
    public DataStreamBindingHtml(RequestContext ctx, IdEncoders idEncoders, boolean isSummary, String collectionTitle, IObsSystemDatabase db, Map<String, CustomObsFormat> customFormats) throws IOException
    {
        super(ctx, idEncoders);
        
        this.customFormats = Asserts.checkNotNull(customFormats);
        this.obsFormat = null;
        this.isSummary = isSummary;
        
        if (ctx.getParentID() != null)
        {
            // fetch parent system name
            var parentSys = db.getSystemDescStore().getCurrentVersion(ctx.getParentID());
            this.collectionTitle = collectionTitle.replace("{}", parentSys.getName());
        }
        else
            this.collectionTitle = collectionTitle;
    }
    
    
    public DataStreamBindingHtml(RequestContext ctx, IdEncoders idEncoders, ResourceFormat obsFormat) throws IOException
    {
        super(ctx, idEncoders);
        this.obsFormat = obsFormat;
        this.customFormats = null;
        this.isSummary = false;
        this.collectionTitle = null;
    }
    
    
    @Override
    protected String getCollectionTitle()
    {
        return collectionTitle;
    }
    
    
    @Override
    public void serialize(DataStreamKey key, IDataStreamInfo dsInfo, boolean showLinks) throws IOException
    {
        if (isSummary)
        {
            if (isCollection)
                serializeSummary(key, dsInfo);
            else
                serializeSingleSummary(key, dsInfo);
        }
        else
            serializeDetails(key, dsInfo);
    }
    
    
    protected void serializeSummary(DataStreamKey key, IDataStreamInfo dsInfo) throws IOException
    {
        var dsId = idEncoders.getDataStreamIdEncoder().encodeID(key.getInternalID());
        var dsUrl = ctx.getApiRootURL() + "/" + DataStreamHandler.NAMES[0] + "/" + dsId;
        
        var sysId = idEncoders.getSystemIdEncoder().encodeID(dsInfo.getSystemID().getInternalID());
        var sysUrl = ctx.getApiRootURL() + "/" + SystemHandler.NAMES[0] + "/" + sysId;
        
        renderCard(
            a(dsInfo.getName())
                .withHref(dsUrl)
                .withClass("text-decoration-none"),
            iff(dsInfo.getDescription() != null, div(
                dsInfo.getDescription()
            ).withClasses(CSS_CARD_SUBTITLE)),
            div(
                span("Source System: ").withClass(CSS_BOLD),
                a(dsInfo.getSystemID().getUniqueID()).withHref(sysUrl)
            ),
            div(
                span("Output: ").withClass(CSS_BOLD),
                span(dsInfo.getOutputName())
            ).withClass("mb-2"),
            div(
                span("Valid Time: ").withClass(CSS_BOLD),
                getTimeExtentHtml(dsInfo.getValidTime(), "Always")
            ).withClass("mb-2"),
            div(
                span("Phenomenon Time: ").withClass(CSS_BOLD),
                getTimeExtentHtml(dsInfo.getPhenomenonTimeRange(), "No Data")
            ).withClass("mb-2"),
            div(
                span("Result Time: ").withClass(CSS_BOLD),
                getTimeExtentHtml(dsInfo.getResultTimeRange(), "No Data")
            ).withClass("mb-2"),
            iffElse(isCollection,
                div(
                    span("Observed Properties: ").withClass(CSS_BOLD),
                    div(
                        String.join(", ", Iterables.transform(SWECommonUtils.getProperties(dsInfo.getRecordStructure()),
                            comp -> getComponentLabel(comp)))
                    ).withClass("ps-4")
                ).withClass("mb-2"),
                div(
                    span("Observed Properties: ").withClass(CSS_BOLD),
                    div(
                        each(ImmutableList.copyOf(SWECommonUtils.getProperties(dsInfo.getRecordStructure())),
                            comp -> getPropertyHtml(comp))
                    ).withClass("ps-4")
                ).withClass("mb-2")
            ),
            iff(!isCollection,
                div(
                    span("Available Observation Formats: ").withClass(CSS_BOLD),
                    div(
                        each(SWECommonUtils.getAvailableFormats(dsInfo, customFormats), f -> {
                            var fUrl = URLEncoder.encode(f, StandardCharsets.UTF_8);
                            return div(
                                span(f + ": "),
                                a("Schema").withHref(dsUrl + "/schema?obsFormat=" + fUrl).withClass("small"),
                                span(" - "),
                                a("Observations").withHref(dsUrl + "/observations?f=" + fUrl).withClass("small")
                            );
                        })
                    ).withClass("ps-4")
                ).withClass("mb-2")
            ),
            iff(isCollection,
                p(
                    a("Details").withHref(dsUrl).withClasses(CSS_LINK_BTN_CLASSES),
                    a("Schema").withHref(dsUrl + "/schema").withClasses(CSS_LINK_BTN_CLASSES),
                    a("Observations").withHref(dsUrl + "/observations").withClasses(CSS_LINK_BTN_CLASSES)
                ).withClass("mt-4")
            )
         );
    }
    
    
    protected void serializeSingleSummary(DataStreamKey key, IDataStreamInfo dsInfo) throws IOException
    {
        writeHeader();
        serializeSummary(key, dsInfo);
        writeFooter();
        writer.flush();
    }
    
    
    protected void serializeDetails(DataStreamKey key, IDataStreamInfo dsInfo) throws IOException
    {
        writeHeader();
        
        div(
            h3(dsInfo.getName()),
            h5("Record Structure"),
            small(
                getComponentHtml(dsInfo.getRecordStructure()),
                getEncodingHtml(dsInfo.getRecordEncoding())
            )
        ).render(html);
        
        writeFooter();
        writer.flush();
    }
}
