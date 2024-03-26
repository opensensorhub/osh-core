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

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import org.sensorhub.api.common.IdEncoders;
import org.sensorhub.impl.service.consys.ServiceErrors;
import org.vast.unit.UnitParserUCUM;
import org.vast.util.TimeExtent;
import j2html.rendering.HtmlBuilder;
import j2html.rendering.IndentedHtml;
import j2html.tags.ContainerTag;
import j2html.tags.DomContent;
import j2html.tags.Tag;
import j2html.tags.UnescapedText;
import j2html.tags.specialized.ATag;
import net.opengis.gml.v32.AbstractGeometry;
import net.opengis.gml.v32.LineString;
import net.opengis.gml.v32.Polygon;
import net.opengis.swe.v20.AllowedTokens;
import net.opengis.swe.v20.AllowedValues;
import net.opengis.swe.v20.BlockComponent;
import net.opengis.swe.v20.DataChoice;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataEncoding;
import net.opengis.swe.v20.DataRecord;
import net.opengis.swe.v20.HasCodeSpace;
import net.opengis.swe.v20.HasConstraints;
import net.opengis.swe.v20.HasUom;
import net.opengis.swe.v20.RangeComponent;
import net.opengis.swe.v20.ScalarComponent;
import net.opengis.swe.v20.Vector;
import static j2html.TagCreator.*;


/**
 * <p>
 * Base class for all HTML resource formatter
 * </p>
 * 
 * @param <K> Resource Key
 * @param <V> Resource Objects
 *
 * @author Alex Robin
 * @since March 31, 2022
 */
public abstract class ResourceBindingHtml<K, V> extends ResourceBinding<K, V>
{
    protected static final DomContent NBSP = new UnescapedText("&nbsp;");
    protected static final String DICTIONARY_TAB_NAME = "osh_semantics";
    protected static final String BLANK_TAB = "_blank";
    protected static final String[] CSS_CARD_TITLE = {"card-title", "text-primary"};
    protected static final String[] CSS_CARD_SUBTITLE = {"card-subtitle", "text-muted", "mb-2"};
    protected static final String[] CSS_LINK_BTN_CLASSES = {"me-2", "btn", "btn-sm", "btn-outline-primary"};
    protected static final String CSS_CARD_TEXT = "card-text";
    protected static final String CSS_BOLD = "fw-bold";
    
    protected Writer writer;
    protected HtmlBuilder<Writer> html;
    protected boolean isCollection;
    protected UnitParserUCUM uomParser;
    
    
    protected ResourceBindingHtml(RequestContext ctx, IdEncoders idEncoders) throws IOException
    {
        super(ctx, idEncoders);
        this.writer = new BufferedWriter(new OutputStreamWriter(ctx.getOutputStream(), StandardCharsets.UTF_8));
        this.html = IndentedHtml.into(writer);
        
        ctx.setResponseFormat(ResourceFormat.HTML);
    }
    
    
    protected void writeHeader() throws IOException
    {
        html.appendStartTag(html().getTagName()).completeTag();
        
        // head
        head(
                title("OpenSensorHub - Connected Systems API"),
                meta().withCharset("UTF-8"),
                styleWithInlineFile("/css/bootstrap.min.css")
                        .attr("integrity", "sha384-1BmE4kWBq78iYhFldvKuhfTAU6auU8tT94WrHftjDbrCEXSU1oBoqyl2QvZ6jIW3"),
                styleWithInlineFile("/css/bootstrap-icons.css"),
                scriptWithInlineFile_min("/js/bootstrap.bundle.min.js")
                        .attr("integrity", "sha384-ka7Sk0Gln4gmtz2MlQnikT1wXgYsOg+OMhuP+IlRH9sENBO0LRn5q+8nbTov4+1p"),
                style()
                        .withText(
                                ".accordion-button { background-color: #e7f1ff; }"
                        ),
                getExtraHeaderContent()
        )
        .render(html);
        
        // start body
        html.appendStartTag(body().getTagName()).completeTag();
        
        // nav bar
        div(
            nav(
                div(
                    /*img().withSrc("https://opensensorhub.files.wordpress.com/2017/08/opensensorhub-logo2.png")
                         .withHeight("60px")*/
                    getBreadCrumbs()
                ),
                span(
                    text("View as "),
                    getAlternateFormats()
                )
            )
            .withClasses("navbar", "navbar-light", "navbar-collapse", "d-flex", "justify-content-between", "align-items-center")
        )
        .withClasses("container", "bg-light")
        .render(html);
        
        // content panel
        html.appendStartTag(div().getTagName())
            .appendAttribute("class", "container py-4")
            .completeTag();
        
        if (isCollection && getCollectionTitle() != null)
            h3(getCollectionTitle()).withStyle("margin-bottom: 60px").render(html);
    }
    
    
    protected String getCollectionTitle()
    {
        // to be overriden by sub classes
        return null;
    }
    
    
    protected DomContent getBreadCrumbs()
    {
        var linkBuilder = new StringBuilder(ctx.getApiRootURL());
        var pathElts = Arrays.asList(ctx.getRequestPath().split("/"));
        
        return tag(null).with(
            span(a("api").withHref(linkBuilder.toString())),
            each(pathElts, item -> {
                linkBuilder.append(item).append('/');
                return span(a(item).withHref(linkBuilder.toString()), text(" / "));
            })
        );
    }
    
    
    protected DomContent getAlternateFormats()
    {
        var jsonQueryParams = new HashMap<>(ctx.getParameterMap());
        jsonQueryParams.remove("format"); // remove format in case it's set
        jsonQueryParams.put("f", new String[] {ResourceFormat.JSON.getMimeType()});
        
        return span(
            a("JSON").withHref(ctx.getRequestUrlWithQuery(jsonQueryParams))
        );
    }
    
    
    protected DomContent getExtraHeaderContent()
    {
        return null;
    }
    
    
    protected void writeFooter() throws IOException
    {
        html.appendEndTag(div().getTagName());
        html.appendEndTag(body().getTagName());
        html.appendEndTag(html().getTagName());
    }
    
    
    protected void renderCard(String title, DomContent... content) throws IOException
    {
        getCard(title, content).render(html);
    }
    
    
    protected void renderCard(ATag title, DomContent... content) throws IOException
    {
        getCard(h5(title).withClasses(CSS_CARD_TITLE), content).render(html);
    }
    
    
    protected void renderCard(Tag<?> title, DomContent... content) throws IOException
    {
        getCard(title, content).render(html);
    }
    
    
    protected ContainerTag<?> getCard(String title, DomContent... content)
    {
        return getCard(title != null ? h5(title).withClasses(CSS_CARD_TITLE) : null, content);
    }
    
    
    protected ContainerTag<?> getCard(Tag<?> title, DomContent... content)
    {
        return div()
            .withClass("card mt-3")
            .with(
                div()
                .withClass("card-body")
                .with(
                    title,
                    each(content)
                 )
            );
    }
    
    
    protected ContainerTag<?> getSection(String title, DomContent... content)
    {
        return getSection(title != null ? h6(title).withClasses(CSS_CARD_TITLE) : null, content);
    }
    
    
    protected ContainerTag<?> getSection(Tag<?> title, DomContent... content)
    {
        return div()
            .withClass("mt-4")
            .with(
                title,
                each(content)
            );
    }
    
    
    protected DomContent getPropertyHtml(DataComponent comp)
    {
        return div(
            span(getComponentLabel(comp))
                .attr("title", comp.getDescription()),
            iff(comp.getDefinition() != null,
                a("(" + comp.getDefinition() + ")")
                    .withClass("small")
                    .withHref(comp.getDefinition())
                    .withTarget(DICTIONARY_TAB_NAME)
            )
        ).withClass("card-text");
    }
    
    
    protected DomContent getComponentOneLineHtml(DataComponent comp)
    {
        DomContent value = null;
        
        if (comp instanceof ScalarComponent)
        {
            value = span(comp.getData().getStringValue());
        }
        else if (comp instanceof RangeComponent)
        {
            value = span(comp.getData().getStringValue(0) + " to " + 
                         comp.getData().getStringValue(1));
        }
        else
            return null;
        
        return div()
            .withClass(CSS_CARD_TEXT)
            .with(
                span(getComponentLabel(comp))
                    .withTitle(comp.getDescription())
                    .withClass(CSS_BOLD),
                getLinkIcon(comp.getDefinition(), comp.getDefinition()),
                span(": "),
                value,
                comp instanceof HasUom ? getUomText((HasUom)comp) : null
            );
    }
    
    
    protected DomContent getComponentHtml(DataComponent comp)
    {
        var content = div();
        
        if (comp.getDescription() != null)
        {
            content.with(
                span(comp.getDescription()).withClasses(CSS_CARD_SUBTITLE)
            );
        }
        
        content.with(div(
            span("Type").withClass(CSS_BOLD),
            span(": "),
            span(comp.getClass().getSimpleName().replace("Impl", ""))
        ));
        
        if (comp.getName() != null)
        {
            content.with(div(
                span("Field Name").withClass(CSS_BOLD),
                span(": "),
                span(comp.getName())
            ));
        }
        
        if (comp.getDefinition() != null)
        {
            content.with(div(
                span("Definition").withClass(CSS_BOLD),
                span(": "),
                a(comp.getDefinition())
                    .withHref(comp.getDefinition())
                    .withTarget(DICTIONARY_TAB_NAME)
            ));
        }
        
        if (comp instanceof HasUom && ((HasUom)comp).getUom() != null)
        {
            DomContent uom;
            var code = ((HasUom)comp).getUom().getCode();
            if (code != null)
            {
                uom = getUomText((HasUom)comp);
            }
            else
            {
                var href = ((HasUom)comp).getUom().getHref();
                uom = a(href)
                    .withHref(href)
                    .withTarget(DICTIONARY_TAB_NAME);
            }
            
            content.with(div(
                span("Unit of measure").withClass(CSS_BOLD),
                span(": "),
                uom
            ));
        }
        
        if (comp instanceof HasCodeSpace && ((HasCodeSpace)comp).getCodeSpace() != null)
        {
            var codeSpace = ((HasCodeSpace)comp).getCodeSpace();
            content.with(div(
                span("Codespace").withClass(CSS_BOLD),
                span(": "),
                a(codeSpace)
                    .withHref(codeSpace)
                    .withTarget(DICTIONARY_TAB_NAME)
            ));
        }
        
        if (comp instanceof HasConstraints)
        {
            var constraint = ((HasConstraints<?>)comp).getConstraint();
            
            if (constraint instanceof AllowedValues)
            {
                var allowedVals = (AllowedValues)constraint;
                var values = "";
                
                if (allowedVals.getNumValues() > 0)
                {
                    for (double val: allowedVals.getValueList())
                        values += val + ", ";
                }
                
                if (allowedVals.getNumIntervals() > 0)
                {
                    for (double[] range: allowedVals.getIntervalList())
                        values += "[" + range[0] + "-" + range[1] + "], ";
                }
                
                if (values.length() > 2)
                    values = values.substring(0, values.length()-2);
                
                content.with(div(
                    span("Allowed Values").withClass(CSS_BOLD),
                    span(": "),
                    span(values)
                ));
            }
            
            else if (constraint instanceof AllowedTokens)
            {
                var allowedVals = (AllowedTokens)constraint;
                var values = "";
                
                if (allowedVals.getNumValues() > 0)
                {
                    for (var val: allowedVals.getValueList())
                        values += val + ", ";
                }
                
                if (values.length() > 2)
                    values = values.substring(0, values.length()-2);
                
                content.with(div(
                    span("Allowed Values").withClass(CSS_BOLD),
                    span(": "),
                    span(values)
                ));
            }
        }
        
        // process sub-components
        
        if (comp instanceof DataRecord || comp instanceof Vector || comp instanceof DataChoice)
        {
            if (comp instanceof Vector)
            {
                var refFrame = ((Vector)comp).getReferenceFrame();
                var localFrame = ((Vector)comp).getLocalFrame();
                
                if (refFrame != null)
                {
                    content.with(div(
                        span("Reference Frame").withClass(CSS_BOLD),
                        span(": "),
                        a(refFrame)
                            .withHref(refFrame)
                            .withTarget(DICTIONARY_TAB_NAME)
                    ));
                }
                
                if (localFrame != null)
                {
                    content.with(div(
                        span("Local Frame").withClass(CSS_BOLD),
                        span(": "),
                        a(localFrame)
                            .withHref(localFrame)
                            .withTarget(DICTIONARY_TAB_NAME)
                    ));
                }
            }
            
            for (int i = 0; i < comp.getComponentCount(); i++)
            {
                var child = comp.getComponent(i);
                var html = getComponentHtml(child);
                content.with(html);
            }
        }
        else if (comp instanceof BlockComponent)
        {
            var html = getComponentHtml(((BlockComponent) comp).getElementType());
            content.with(html);
        }
        
        return getCard(getComponentLabel(comp), content).withClasses("card", "mt-3");
    }
    
    
    protected DomContent getUomText(HasUom comp)
    {
        var code = comp.getUom().getCode();
        if (code == null)
            return span();
        
        if (uomParser == null)
            uomParser = new UnitParserUCUM();
        
        if (!UnitParserUCUM.isValidUnit(code))
            return span(code).attr("style", "color: red");
        
        var symbol = uomParser
            .findUnit(code)
            .getPrintSymbol();
        
        if ("1".equals(symbol))
            symbol = "";
        
        //return span(symbol == null || code.equals(symbol) ? code : symbol + " (" + code + ")");
        var text = span(symbol == null ? code : symbol);
        if (symbol != null)
            text.withTitle(code);
        
        return text;
    }
    
    
    protected DomContent getEncodingHtml(DataEncoding enc)
    {
        return div();
    }
    
    
    protected String getComponentLabel(DataComponent comp)
    {
        if (comp.getLabel() != null)
            return comp.getLabel();
        
        return getPrettyName(comp.getName());
    }
    
    
    protected String getPrettyName(String name)
    {
        StringBuilder buf = new StringBuilder(name.replace("_", " "));
        for (int i=0; i<buf.length()-1; i++)
        {
            char c = buf.charAt(i);
            
            if (i == 0 || buf.charAt(i-1) == ' ')
            {
                char newcar = Character.toUpperCase(c);
                buf.setCharAt(i, newcar);
            }
            
            else if (Character.isUpperCase(c) && Character.isLowerCase(buf.charAt(i-1)))
            {
                buf.insert(i, ' ');
                i++;
            }
        }
        
        return buf.toString();
    }
    
    
    protected Tag<?> getTimeExtentHtml(TimeExtent te, String textIfNull)
    {
        if (te == null)
            return span(textIfNull);
        
        return div(
            span(te.begin().truncatedTo(ChronoUnit.SECONDS).toString()), br(),
            span(te.end().truncatedTo(ChronoUnit.SECONDS).toString() + (te.endsNow() ? " (Now)" : ""))
        ).withClass("ps-4");
    }
    
    
    protected Tag<?> getTimeExtentHtmlSingleLine(TimeExtent te, String textIfNull)
    {
        if (te == null)
            return span(textIfNull);
        
        var startHasTime = (te.begin().getEpochSecond() % 86400) != 0;
        var stopHasTime = (te.end().getEpochSecond() % 86400) != 0;
        
        return span(
            (startHasTime ?
                te.begin().truncatedTo(ChronoUnit.SECONDS) :
                te.begin().atOffset(ZoneOffset.UTC).toLocalDate()
            ) + " to " +
            (te.endsNow() ? "Now" : stopHasTime ? 
                te.end().truncatedTo(ChronoUnit.SECONDS) :
                te.end().atOffset(ZoneOffset.UTC).toLocalDate()
            )
        );
    }
    
    
    protected Tag<?> getGeometryHtml(AbstractGeometry geom)
    {
        if (geom == null)
            return span("None");
        
        // don't display large geometries!
        int size = 0;
        String text = "";
        if (geom instanceof Polygon)
        {
            text = "POLYGON(...)";
            size = ((Polygon) geom).getExterior().getPosList().length;
        }
        else if (geom instanceof LineString)
        {
            text = "LINESTRING(...)";
            size = ((LineString) geom).getPosList().length;
        }
        
        return div(
            span(size <= 10 ? geom.toString() : text)
        ).withClass("ps-4");
    }
    
    
    protected DomContent getLinkIcon(String href, String title)
    {
        return sup(a(" ")
            .withClass("text-decoration-none bi-box-arrow-up-right small")
            .withHref(href)
            .withTitle(title)
            .withTarget(DICTIONARY_TAB_NAME));
    }
    
    
    protected void writePagination(Collection<ResourceLink> pagingLinks) throws IOException
    {
        var prevLink = pagingLinks.stream()
            .filter(link -> ResourceLink.REL_PREV.equals(link.rel))
            .findFirst()
            .orElse(new ResourceLink());
        
        var nextLink = pagingLinks.stream()
            .filter(link -> ResourceLink.REL_NEXT.equals(link.rel))
            .findFirst()
            .orElse(new ResourceLink());
        
        nav(
            ul(
                li(
                    a().withHref(prevLink.href)
                       .withRel(prevLink.rel)
                       .withText("<< Previous")
                       .withClasses("page-link")
                )
                .withClasses("page-item", prevLink.href == null ? "disabled" : null),
                    
                li(
                    
                    a().withHref(nextLink.href)
                       .withRel(nextLink.rel)
                       .withText("Next >>")
                       .withClasses("page-link")
                )
                .withClasses("page-item", nextLink.href == null ? "disabled" : null)
            )
            .withClasses("pagination", "pagination-sm", "my-4")
        )
        .attr("style", "position:absolute; top: 85px;")
        .render(html);
    }
    
    
    @Override
    public void startCollection() throws IOException
    {
        isCollection = true;
        writeHeader();
    }
    
    
    @Override
    public void endCollection(Collection<ResourceLink> links) throws IOException
    {
        writePagination(links);
        writeFooter();
        writer.flush();
    }
    
    
    @Override
    public V deserialize() throws IOException
    {
        throw ServiceErrors.unsupportedFormat("text/html");
    }
}