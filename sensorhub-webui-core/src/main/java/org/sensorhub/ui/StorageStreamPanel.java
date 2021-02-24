/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2012-2019 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.sensorhub.api.persistence.DataFilter;
import org.sensorhub.api.persistence.IMultiSourceStorage;
import org.sensorhub.api.persistence.IRecordStorageModule;
import org.sensorhub.api.persistence.IRecordStoreInfo;
import org.sensorhub.ui.api.UIConstants;
import org.sensorhub.ui.chartjs.Chart;
import org.sensorhub.ui.chartjs.Chart.SliderChangeListener;
import org.vast.data.DataValue;
import org.vast.swe.SWEDataTypeUtils;
import org.vast.swe.ScalarIndexer;
import org.vast.util.DateTimeFormat;
import com.google.common.collect.Sets;
import com.google.common.io.Resources;
import com.vaadin.data.Item;
import com.vaadin.data.Property.ValueChangeEvent;
import com.vaadin.data.Property.ValueChangeListener;
import com.vaadin.data.util.converter.Converter;
import com.vaadin.server.FontAwesome;
import com.vaadin.shared.ui.MarginInfo;
import com.vaadin.shared.ui.label.ContentMode;
import com.vaadin.ui.Alignment;
import com.vaadin.ui.Button;
import com.vaadin.ui.Component;
import com.vaadin.ui.FormLayout;
import com.vaadin.ui.GridLayout;
import com.vaadin.ui.HorizontalLayout;
import com.vaadin.ui.Label;
import com.vaadin.ui.Panel;
import com.vaadin.ui.Table;
import com.vaadin.ui.TextField;
import com.vaadin.ui.VerticalLayout;
import com.vaadin.ui.Button.ClickEvent;
import com.vaadin.ui.Button.ClickListener;
import com.vividsolutions.jts.geom.Coordinate;
import net.opengis.swe.v20.DataBlock;
import net.opengis.swe.v20.DataComponent;
import net.opengis.swe.v20.DataRecord;
import net.opengis.swe.v20.HasUom;
import net.opengis.swe.v20.ScalarComponent;
import net.opengis.swe.v20.Time;
import net.opengis.swe.v20.Vector;


/**
 * <p>
 * This represents the panel displaying info for a single data stream
 * </p>
 *
 * @author Alex Robin <alex.robin@sensiasoftware.com>
 * @since Jun 24, 2019
 */
public class StorageStreamPanel extends Panel
{
    private static final long serialVersionUID = 6169765057074245360L;
    static final int SECONDS_PER_HOUR = 3600;
    static final int SECONDS_PER_DAY = 3600*24;
    
    transient SWEDataTypeUtils sweUtils = new SWEDataTypeUtils();
    Chart detailChart;
    Chart navigatorChart;
    Table table;
    
    IRecordStorageModule<?> storage;
    IRecordStoreInfo dsInfo;
    double[] fullTimeRange;
    double[] timeRange;
    Set<String> producerIDs = null;
        
    
    public StorageStreamPanel(int index, IRecordStorageModule<?> storage, IRecordStoreInfo dsInfo)
    {
        this.storage = storage;
        this.dsInfo = dsInfo;
        
        setCaption("Stream #" + index + ": " + getPrettyName(dsInfo.getRecordDescription()));
        VerticalLayout layout = new VerticalLayout();
        setContent(layout);        
        layout.setMargin(true);
        layout.setSpacing(true);
        
        // producer selector (if multisource storage)
        Component form = buildForm();
        layout.addComponent(form);
        
        // grid layout for data structure and table
        GridLayout grid = new GridLayout(2, 2);
        grid.setWidth(100.0f, Unit.PERCENTAGE);
        grid.setSpacing(true);
        grid.setColumnExpandRatio(0, 0.2f);
        grid.setColumnExpandRatio(1, 0.8f);
        grid.setDefaultComponentAlignment(Alignment.TOP_LEFT);
        layout.addComponent(grid);
        
        // data structure
        DataComponent dataStruct = dsInfo.getRecordDescription();
        Component sweForm = new SWECommonForm(dataStruct);
        grid.addComponent(sweForm);
        
        // data table
        Component tableArea = buildTable();
        grid.addComponent(tableArea);        
    }
    
    
    @SuppressWarnings("serial")
    protected Component buildTimeRangeRow()
    {
        final HorizontalLayout layout = new HorizontalLayout();
        layout.setSpacing(true);
        layout.setDefaultComponentAlignment(Alignment.MIDDLE_LEFT);
        
        timeRange = storage.getRecordsTimeRange(dsInfo.getName());
        String timeRangeText = Double.isNaN(timeRange[0]) ? "Empty" : 
                new DateTimeFormat().formatIso(timeRange[0], 0) + "  -  " +
                new DateTimeFormat().formatIso(timeRange[1], 0);
        Label timeRangeLabel = new Label(timeRangeText);
        timeRangeLabel.setContentMode(ContentMode.HTML);
        layout.addComponent(timeRangeLabel);
        layout.setCaption("Time Range");
        
        final Button btn = new Button(FontAwesome.BAR_CHART);
        btn.setDescription("Show Histogram");
        btn.addStyleName(UIConstants.STYLE_SMALL);
        btn.addStyleName(UIConstants.STYLE_QUIET);
        btn.addClickListener(new ClickListener()
        {            
            @Override
            public void buttonClick(ClickEvent event)
            {
                final VerticalLayout panelLayout = (VerticalLayout)layout.getParent().getParent();
                
                if (detailChart == null)
                {
                    // add histogram time line
                    Component timeline = buildHistogram();
                    int idx = panelLayout.getComponentIndex(layout.getParent());
                    panelLayout.addComponent(timeline, idx+1);
                    btn.setDescription("Hide Histogram");
                }
                else
                {
                    // remove histogram                    
                    panelLayout.removeComponent(detailChart.getParent());
                    btn.setDescription("Show Histogram");
                    detailChart = null;
                    navigatorChart = null;
                }
            }
        });
        layout.addComponent(btn);
        
        return layout;
    }
    
    
    @SuppressWarnings("serial")
    protected Component buildForm()
    {
        FormLayout formLayout = new FormLayout();
        formLayout.setMargin(false);
        formLayout.setSpacing(false);
        
        // producer filter
        if (storage instanceof IMultiSourceStorage && ((IMultiSourceStorage<?>) storage).getProducerIDs().size() > 1)
        {
            final TextField producerFilter = new TextField("Producers IDs");
            producerFilter.addStyleName(UIConstants.STYLE_SMALL);
            producerFilter.setDescription("Comma separated list of data producer IDs to view data for");
            producerFilter.setValue("ALL");
            formLayout.addComponent(producerFilter);
            
            producerFilter.addValueChangeListener(new ValueChangeListener() {
                @Override
                public void valueChange(ValueChangeEvent event)
                {
                    String tx = producerFilter.getValue().trim();
                    String[] items = tx.replaceAll(" ", "").split(",");
                    if (items.length > 0)
                    {
                        producerIDs = Sets.newTreeSet(Arrays.asList(items));
                        producerIDs.retainAll(((IMultiSourceStorage<?>) storage).getProducerIDs());
                        if (producerIDs.isEmpty())
                            producerIDs = null;
                    }
                    else
                        producerIDs = null;
                    
                    if (producerIDs == null)
                        producerFilter.setValue("ALL");
                    
                    updateTable();
                    updateHistogram(detailChart, timeRange);
                    updateHistogram(navigatorChart, fullTimeRange);
                }                        
            });            
        }
        
        // time range panel
        formLayout.addComponent(buildTimeRangeRow());
        
        return formLayout;
    }
    
    
    @SuppressWarnings("serial")
    protected Component buildHistogram()
    {
        String dsName = dsInfo.getName();
                    
        VerticalLayout layout = new VerticalLayout();
        //layout.setMargin(false);
        layout.setMargin(new MarginInfo(false, true, true, false));
        layout.setSpacing(true);
        
        try
        {
            fullTimeRange = timeRange = storage.getRecordsTimeRange(dsName);
            roundTimePeriod(fullTimeRange);
            String fullRangeData = getHistogramData(fullTimeRange);
            
            // detail chart
            detailChart = new Chart("chart-" + dsName);
            detailChart.setWidth(100, Unit.PERCENTAGE);
            detailChart.setHeight(120, Unit.PIXELS);
            String jsConfig = Resources.toString(getClass().getResource("chartjs_timeline_chart.js"), StandardCharsets.UTF_8);
            detailChart.setChartConfig(jsConfig, fullRangeData);
                        
            // rangeslider chart
            navigatorChart = new Chart("slider-" + dsInfo.getName(), true);
            navigatorChart.addStyleName("storage-navslider");
            navigatorChart.setWidth(100, Unit.PERCENTAGE);
            navigatorChart.setHeight(52, Unit.PIXELS);
            jsConfig = Resources.toString(getClass().getResource("chartjs_timeline_rangeslider.js"), StandardCharsets.UTF_8);
            navigatorChart.setChartConfig(jsConfig, fullRangeData);
            
            navigatorChart.addSliderChangeListener(new SliderChangeListener()
            {                
                @Override
                public void onSliderChange(double min, double max)
                {
                    timeRange  = new double[] {min, max};
                    updateTable();
                    updateHistogram(detailChart, timeRange);
                }
            });
            
            layout.addComponent(detailChart);
            layout.addComponent(navigatorChart);
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
        
        return layout;
    }
    
    
    void roundTimePeriod(double[] timeRange)
    {
        double duration = timeRange[1] - timeRange[0];
        
        if (duration > 5*SECONDS_PER_DAY)
        {
            // round time period to a full number of days
            timeRange[0] = Math.floor(timeRange[0] / SECONDS_PER_DAY) * SECONDS_PER_DAY;
            timeRange[1] = Math.ceil(timeRange[1] / SECONDS_PER_DAY) * SECONDS_PER_DAY;
        }
        else
        {
            // round time period to a full number of hours
            timeRange[0] = Math.floor(timeRange[0] / SECONDS_PER_HOUR) * SECONDS_PER_HOUR;
            timeRange[1] = Math.ceil(timeRange[1] / SECONDS_PER_HOUR) * SECONDS_PER_HOUR;
        }
    }
    
    
    void updateHistogram(Chart chart, double[] timeRange)
    {
        if (chart != null)
        {
            String jsData = getHistogramData(timeRange);
            chart.updateChartData(0, jsData);
        }
    }
    
    
    String getHistogramData(double[] timeRange)
    {
        StringBuilder jsData = new StringBuilder("[");
        Coordinate[] counts = getEstimatedCounts(timeRange);
        for (Coordinate p: counts)
        {
            jsData.append("{t:").append(p.x).append(',');
            jsData.append("y:").append(p.y).append("},");
        }
        jsData.append("]");
        return jsData.toString();
    }
    
    
    Coordinate[] getEstimatedCounts(double[] timeRange)
    {
        // compute number of bins to obtain round time slots
        double duration = timeRange[1] - timeRange[0];
        double binSize = getBinSize(duration);
        if (binSize > 3600)
            timeRange[0] = Math.floor(timeRange[0] / 86400) * 86400;
        int numBins = (int)(duration/binSize) + 1;
        
        // compute time points
        double timeStamp = timeRange[0];
        double[] times = new double[numBins+1];
        for (int i = 0; i <= numBins; i++)
        {
            times[i] = timeStamp;
            timeStamp += binSize;
        }
        
        // request record count data from storage
        int[] counts;
        if (producerIDs != null)
        {
            counts = new int[numBins];
            for (String producerID: producerIDs)
            {
                int[] newCounts = ((IMultiSourceStorage<?>)storage).getDataStore(producerID).getEstimatedRecordCounts(dsInfo.getName(), times);
                for (int i = 0; i < counts.length; i++)
                    counts[i] += newCounts[i];
            }
        }
        else
            counts = storage.getEstimatedRecordCounts(dsInfo.getName(), times);
        
        // create series item array
        // set time coordinate as unix timestamp in millis
        Coordinate[] items = new Coordinate[counts.length];
        for (int i = 0; i < numBins; i++)
            items[i] = new Coordinate(times[i]*1000, (double)counts[i]);
        return items;
    }
    
    
    double getBinSize(double duration)
    {
        // we want approx 100 bins, but rounded to full days, hours or minutes
        double binSize = duration / 100.;
        double binSizeDays = binSize / SECONDS_PER_DAY;
        if (binSizeDays < 1.0)
        {
            double binSizeHours = 24 * binSizeDays;
            if (binSizeHours < 1.0)
            {
                int binSizeMinutes = (int)Math.round(60 * binSizeHours);
                if (binSizeMinutes == 0)
                    return 60.0;
                
                while (60 % binSizeMinutes != 0)
                    binSizeMinutes++;
                return binSizeMinutes * 60;
            }                
            
            int binSizeHoursI = (int)binSizeHours;
            while (24 % binSizeHoursI != 0)
                binSizeHoursI++;
            return binSizeHoursI * SECONDS_PER_HOUR;
        }
        else
            return (int)Math.round(binSize);
    }
    
    
    protected void updateTable()
    {
        List<ScalarIndexer> indexers = (List<ScalarIndexer>)table.getData();
        table.removeAllItems();
        
        Iterator<DataBlock> it = storage.getDataBlockIterator(new DataFilter(dsInfo.getName()) {
            @Override
            public double[] getTimeStampRange()
            {
                return timeRange;
            }

            @Override
            public Set<String> getProducerIDs()
            {
                return producerIDs;
            }            
        });
        
        int count = 0;
        int pageSize = 10;
        while (it.hasNext() && count < pageSize)
        {
            DataBlock dataBlk = it.next();
            
            Item item = table.addItem(count);
            int i = 0;
            for (Object colId: table.getContainerPropertyIds())
            {
                String value = indexers.get(i).getStringValue(dataBlk);
                item.getItemProperty(colId).setValue(value);
                i++;
            }            
                        
            count++;
        }
    }
    
    
    protected Component buildTable()
    {
        table = new Table();
        table.setWidth(100, Unit.PERCENTAGE);
        table.setPageLength(10);
        
        // add column names
        List<ScalarIndexer> indexers = new ArrayList<>();
        DataComponent recordDef = dsInfo.getRecordDescription();
        addColumns(recordDef, recordDef, table, indexers);
        table.setData(indexers);
        
        // populate table data
        updateTable();
        
        return table;
    }
    
    
    @SuppressWarnings("serial")
    protected void addColumns(DataComponent recordDef, DataComponent component, Table table, List<ScalarIndexer> indexers)
    {
        if (component instanceof ScalarComponent)
        {
            // add column names
            String propId = component.getName();
            String label = getPrettyName(component);
            table.addContainerProperty(propId, String.class, null, label, null, null);
            
            // correct time formatting
            String uomUri = (component instanceof HasUom) ? ((HasUom)component).getUom().getHref() : null;
            if (Time.ISO_TIME_UNIT.equals(uomUri))
            {
                table.setConverter(propId, new Converter<String, String>() {
                    DateTimeFormat dateFormat = new DateTimeFormat();
                    
                    @Override
                    public String convertToPresentation(String value, Class<? extends String> targetType, Locale locale) throws ConversionException
                    {
                        if (value == null)
                            return "";
                        return sweUtils.getDoubleOrTimeAsString(Double.parseDouble(value), true);
                    }
                    
                    @Override
                    public String convertToModel(String value, Class<? extends String> targetType, Locale locale)
                    {
                        return null;
                    }
                    
                    @Override
                    public Class<String> getModelType()
                    {
                        return String.class;
                    }
                    
                    @Override
                    public Class<String> getPresentationType()
                    {
                        return String.class;
                    }                        
                });
            }
            
            // prepare indexer for reading from datablocks
            indexers.add(new ScalarIndexer(recordDef, (ScalarComponent)component));
        }
        
        // call recursively for records
        else if (component instanceof DataRecord || component instanceof Vector)
        {
            for (int i = 0; i < component.getComponentCount(); i++)
            {
                DataComponent child = component.getComponent(i);
                addColumns(recordDef, child, table, indexers);
            }
        }
    }
    
    
    protected String getPrettyName(DataComponent dataComponent)
    {
        String label = dataComponent.getLabel();
        if (label == null)
            label = DisplayUtils.getPrettyName(dataComponent.getName());
        return label;
    }
}
