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
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.data.IDataStreamInfo;
import org.sensorhub.api.database.IObsSystemDatabase;
import org.sensorhub.api.datastore.feature.FoiFilter;
import org.sensorhub.api.datastore.obs.DataStreamKey;
import org.sensorhub.api.datastore.obs.ObsFilter;
import org.sensorhub.api.datastore.obs.ObsStatsQuery;
import org.sensorhub.api.module.IModule;
import org.sensorhub.api.service.HttpServiceConfig;
import org.sensorhub.impl.service.AbstractHttpServiceModule;
import org.sensorhub.ui.api.UIConstants;

import org.sensorhub.ui.chartjs.Chart;
import org.sensorhub.ui.table.PagedTableControls;
import org.sensorhub.ui.table.LazyLoadingObsContainer;
import org.sensorhub.ui.table.PagedTable;
import org.sensorhub.ui.table.PagedTable.PageChangeListener;
import org.sensorhub.ui.table.PagedTable.PagedTableChangeEvent;
import org.vast.swe.ScalarIndexer;
import org.vast.util.DateTimeFormat;
import org.vast.util.TimeExtent;
import com.google.common.io.Resources;
import com.vaadin.v7.data.Property.ValueChangeEvent;
import com.vaadin.v7.data.Property.ValueChangeListener;
import com.vaadin.v7.data.util.converter.Converter;
import com.vaadin.server.FontAwesome;
import com.vaadin.shared.ui.MarginInfo;
import com.vaadin.shared.ui.ContentMode;
import com.vaadin.ui.Alignment;
import com.vaadin.ui.Button;
import com.vaadin.ui.Component;
import com.vaadin.ui.FormLayout;
import com.vaadin.ui.GridLayout;
import com.vaadin.ui.HorizontalLayout;
import com.vaadin.ui.Label;
import com.vaadin.ui.UI;
import com.vaadin.v7.ui.ComboBox;
import com.vaadin.v7.ui.Table;
import com.vaadin.v7.ui.Table.ColumnGenerator;
import com.vaadin.ui.VerticalLayout;
import com.vaadin.ui.Button.ClickEvent;
import com.vaadin.ui.Button.ClickListener;
import org.locationtech.jts.geom.Coordinate;
import net.opengis.swe.v20.DataArray;
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
 * @author Alex Robin
 * @since Jun 24, 2019
 */
@SuppressWarnings("deprecation")
public class DatabaseStreamPanel extends VerticalLayout
{
    private static final long serialVersionUID = 6169765057074245360L;
    static final int SECONDS_PER_HOUR = 3600;
    static final int SECONDS_PER_DAY = 3600*24;
    static final String FOI_COLUMN_ID = "$foiId";
    
    Label timeRangeLabel;
    Chart detailChart;
    Chart navigatorChart;
    PagedTable table;
    
    IObsSystemDatabase db;
    BigId dataStreamID;
    Set<BigId> foiIDs = new HashSet<>();
    IDataStreamInfo dsInfo;
    TimeExtent fullTimeRange;
    TimeExtent zoomTimeRange;
    LazyLoadingObsContainer obsDataContainer;
    String csApiBaseUrl;
    
    
    public DatabaseStreamPanel(IObsSystemDatabase db, IDataStreamInfo dsInfo, BigId dataStreamID)
    {
        this.db = db;
        this.dataStreamID = dataStreamID;
        this.dsInfo = dsInfo;
        
        // get base URL of CONSYS API so we can generate links
        var hub = ((IModule<?>)db).getParentHub();
        hub.getModuleRegistry().getLoadedModules().stream()
            .filter(m -> m.getClass().getSimpleName().equals("ConSysApiService"))
            .findFirst()
            .ifPresent(m -> {
                var conSysApi = (AbstractHttpServiceModule<?>)m; 
                var path = ((HttpServiceConfig)conSysApi.getConfiguration()).endPoint;
                csApiBaseUrl = conSysApi.getHttpServer().getPublicEndpointUrl(path);
            });
        
        setMargin(true);
        setSpacing(true);
        setCaption(getPrettyName(dsInfo.getRecordStructure()));
    }
    
    
    protected void refreshContent()
    {
        removeAllComponents();
        
        if (dsInfo == null)
            return;
        
        setCaption(getPrettyName(dsInfo.getRecordStructure()));
        
        // top level info
        addComponent(buildHeaderInfo());
        zoomTimeRange = fullTimeRange;
        
        // add histogram if it was open before
        if (detailChart != null)
        {
            Component timeline = buildHistogram();
            addComponent(timeline);
        }
        
        // grid layout for data structure and table
        GridLayout grid = new GridLayout(2, 2);
        grid.setWidth(100.0f, Unit.PERCENTAGE);
        grid.setSpacing(true);
        grid.setColumnExpandRatio(0, 0.2f);
        grid.setColumnExpandRatio(1, 0.8f);
        grid.setDefaultComponentAlignment(Alignment.TOP_LEFT);
        addComponent(grid);
        
        // data structure
        DataComponent dataStruct = dsInfo.getRecordStructure();
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
        
        timeRangeLabel = new Label();
        timeRangeLabel.setContentMode(ContentMode.HTML);
        timeRangeLabel.addStyleName(UIConstants.STYLE_SMALL);
        updateTimeRange();
        layout.addComponent(timeRangeLabel);
        layout.setCaption("Time Range:");
        
        final Button btn = new Button(FontAwesome.BAR_CHART);
        btn.setDescription(detailChart == null ? "Show Histogram" : "Hide Histogram");
        btn.setEnabled(dsInfo.getPhenomenonTimeRange() != null);
        btn.addStyleName(UIConstants.STYLE_SMALL);
        btn.addStyleName(UIConstants.STYLE_QUIET);
        layout.addComponent(btn);
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
        
        // refresh button
        Button refreshButton = new Button("Refresh");
        refreshButton.setDescription("Reload data from database");
        refreshButton.setIcon(UIConstants.REFRESH_ICON);
        refreshButton.addStyleName(UIConstants.STYLE_SMALL);
        refreshButton.addStyleName(UIConstants.STYLE_QUIET);
        layout.addComponent(refreshButton);
        layout.setComponentAlignment(refreshButton, Alignment.MIDDLE_LEFT);
        refreshButton.addClickListener(new ClickListener() {
            private static final long serialVersionUID = 1L;
            @Override
            public void buttonClick(ClickEvent event)
            {
                var dsInfo = DatabaseStreamPanel.this.dsInfo = db.getDataStreamStore().get(new DataStreamKey(dataStreamID));
                //refreshContent();
                var fullTimeRange = dsInfo.getPhenomenonTimeRange();
                fullTimeRange = roundTimePeriod(fullTimeRange);
                updateTimeRange();
                updateHistogram(navigatorChart, fullTimeRange);
                updateHistogram(detailChart, zoomTimeRange);
                updateTable();
            }
        });
        
        return layout;
    }
    
    
    protected void updateTimeRange()
    {
        var timeRange = dsInfo.getPhenomenonTimeRange();
        
        String timeRangeText = "- NO DATA -";
        if (timeRange != null)
        {
            fullTimeRange = roundTimePeriod(timeRange);
            Instant begin = timeRange.begin().truncatedTo(ChronoUnit.SECONDS);
            Instant end = timeRange.end().truncatedTo(ChronoUnit.SECONDS);
            timeRangeText = begin + " - " + end;
        }
        
        timeRangeLabel.setValue(timeRangeText);
    }
    
    
    protected Component buildHeaderInfo()
    {
        FormLayout formLayout = new FormLayout();
        formLayout.setMargin(false);
        formLayout.setSpacing(false);
        
        // FOI filter
        var foiEntries = db.getFoiStore().selectEntries(new FoiFilter.Builder()
            .withObservations()
                .withDataStreams(dataStreamID)
                .done()
            .build())
            .map(e -> new SimpleEntry<BigId, String>(
                e.getKey().getInternalID(),
                e.getValue().getName() + " (" + e.getValue().getUniqueIdentifier() + ")"))
            .collect(Collectors.toSet());
        
        if (!foiEntries.isEmpty())
        {
            final ComboBox selectBox = new ComboBox("FOIs");
            selectBox.setNullSelectionAllowed(false);
            selectBox.addStyleName(UIConstants.STYLE_SMALL);
            selectBox.setWidth(50, Unit.EM);
            selectBox.addItem(BigId.NONE);
            selectBox.setItemCaption(BigId.NONE, "ALL");
            foiEntries.stream().forEach(f -> {
                selectBox.addItem(f.getKey());
                selectBox.setItemCaption(f.getKey(), f.getValue());
            });
            /*var selectedId = !foiIDs.isEmpty() ? foiIDs.iterator().next() : BigId.NONE;
            if (selectBox.getItem(selectedId) != null)
                selectBox.select(selectedId);
            else*/
                selectBox.select(BigId.NONE);
            formLayout.addComponent(selectBox);
            
            selectBox.addValueChangeListener(new ValueChangeListener() {
                @Override
                public void valueChange(ValueChangeEvent event)
                {
                    foiIDs.clear();
                    var selectedFoi = (BigId)selectBox.getValue();
                    if (selectedFoi != null && selectedFoi != BigId.NONE)
                        foiIDs.add(selectedFoi);
                    
                    var fullTimeRange = dsInfo.getPhenomenonTimeRange();
                    fullTimeRange = roundTimePeriod(fullTimeRange);
                    updateTimeRange();
                    updateHistogram(navigatorChart, fullTimeRange);
                    updateHistogram(detailChart, zoomTimeRange);
                    updateTable();
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
        VerticalLayout layout = new VerticalLayout();
        layout.setMargin(new MarginInfo(false, true, true, false));
        layout.setSpacing(true);
        
        try
        {
            String fullRangeData = getHistogramData(fullTimeRange);
            
            // detail chart
            detailChart = new Chart("chart-" + dsInfo.getOutputName());
            detailChart.setWidth(100, Unit.PERCENTAGE);
            detailChart.setHeight(120, Unit.PIXELS);
            String jsConfig = Resources.toString(getClass().getResource("chartjs_timeline_chart.js"), StandardCharsets.UTF_8);
            detailChart.setChartConfig(jsConfig, fullRangeData);
            
            // rangeslider chart
            navigatorChart = new Chart("slider-" + dsInfo.getOutputName(), true);
            navigatorChart.addStyleName("storage-navslider");
            navigatorChart.setWidth(100, Unit.PERCENTAGE);
            navigatorChart.setHeight(52, Unit.PIXELS);
            jsConfig = Resources.toString(getClass().getResource("chartjs_timeline_rangeslider.js"), StandardCharsets.UTF_8);
            navigatorChart.setChartConfig(jsConfig, fullRangeData);
            
            navigatorChart.addSliderChangeListener(new Chart.SliderChangeListener()
            {
                @Override
                public void onSliderChange(double min, double max)
                {
                    zoomTimeRange = TimeExtent.period(
                        Instant.ofEpochSecond(Math.round(min)),
                        Instant.ofEpochSecond(Math.round(max)));
                    updateTable();
                    updateHistogram(detailChart, zoomTimeRange);
                }
            });
            
            layout.addComponent(detailChart);
            layout.addComponent(navigatorChart);
        }
        catch (IOException e)
        {
            ((AdminUI)UI.getCurrent()).getOshLogger().error("Error generating histogram", e);
        }
        
        return layout;
    }
    
    
    TimeExtent roundTimePeriod(TimeExtent timeRange)
    {
        var duration = timeRange.duration();
        
        if (duration.toDays() > 5)
        {
            // round time period to a full number of days
            var begin = timeRange.begin().truncatedTo(ChronoUnit.DAYS);
            var end = timeRange.end().truncatedTo(ChronoUnit.DAYS);
            if (timeRange.end().getEpochSecond() % 86400 != 0)
                end = end.plus(1, ChronoUnit.DAYS);
            return TimeExtent.period(begin, end);
        }
        else
        {
            // round time period to a full number of hours
            var begin = timeRange.begin().truncatedTo(ChronoUnit.HOURS);
            var end = timeRange.end().truncatedTo(ChronoUnit.HOURS);
            if (timeRange.end().getEpochSecond() % 3600 != 0)
                end = end.plus(1, ChronoUnit.HOURS);
            return TimeExtent.period(begin, end);
        }
    }
    
    
    void updateHistogram(Chart chart, TimeExtent timeRange)
    {
        if (chart != null)
        {
            String jsData = getHistogramData(timeRange);
            chart.updateChartData(0, jsData);
        }
    }
    
    
    String getHistogramData(TimeExtent timeRange)
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
    
    
    Coordinate[] getEstimatedCounts(TimeExtent timeRange)
    {
        // compute number of bins to obtain round time slots
        long duration = timeRange.duration().getSeconds();
        int binSize = getBinSize(duration);        
        var binDuration = Duration.ofSeconds(binSize);
        
        var filter = new ObsFilter.Builder()
            .withDataStreams(dataStreamID)
            .withPhenomenonTime().fromTimeExtent(timeRange).done();
        if (!foiIDs.isEmpty())
            filter.withFois(foiIDs);
        
        var results = db.getObservationStore().getStatistics(new ObsStatsQuery.Builder()
            .selectObservations(filter.build())
            .withHistogramBinSize(binDuration)
            .aggregateFois(true)
            .build());
        
        var stats = results.findFirst();
        if (stats.isPresent())
        {
            var totalCounts = stats.get().getObsCountsByTime();
            
            // create series item array
            // set time coordinate as unix timestamp in millis
            Coordinate[] items = new Coordinate[totalCounts.length+1];
            long time = timeRange.begin().toEpochMilli();
            for (int i = 0; i < totalCounts.length; i++)
            {
                items[i] = new Coordinate(time, (double)totalCounts[i]);
                time += binSize*1000;
            }
            items[totalCounts.length] = new Coordinate(time, 0.0);
            return items;
        }
        
        return new Coordinate[0];
    }
    
    
    static int[] POSSIBLE_BIN_SIZES = new int[] {
        1, 5, 10, 20, 30, 60, 120, 300, 600, 900, 1200, 1800,
        3600, 3600*2, 3600*4, 3600*6, 3600*8, 3600*12,
        86400, 86400*2, 86400*4, 86400*7, 86400*14, 86400*30,
        86400*30*2, 86400*30*3, 86400*30*4, 86400*30*6, 86400*365
    };
    
    int getBinSize(long duration)
    {
        // we want approx 100 bins, but rounded to full days, hours or minutes
        int exactBinSize = (int)Math.round(duration / 200.);
        
        int idx = Arrays.binarySearch(POSSIBLE_BIN_SIZES, exactBinSize);
        if (idx < 0)
            idx = Math.min(-(idx+1), POSSIBLE_BIN_SIZES.length-1);
        
        return POSSIBLE_BIN_SIZES[idx];
    }
    
    
    protected void updateTable()
    {
        obsDataContainer.updateTimeRange(zoomTimeRange);
        table.setContainerDataSource(obsDataContainer);
        table.setCurrentPage(1);
                
        var cols = new ArrayList<Object>(table.getContainerPropertyIds());
        cols.remove(FOI_COLUMN_ID);
        cols.add(0, FOI_COLUMN_ID);
        table.setVisibleColumns(cols.toArray());
    }
    
    
    protected Component buildTable()
    {
        VerticalLayout tableLayout = new VerticalLayout();
        tableLayout.setMargin(false);
        tableLayout.setSpacing(true);
        
        table = new PagedTable();
        table.setWidth(100, Unit.PERCENTAGE);
        table.setPageLength(10);
        table.addStyleName(UIConstants.STYLE_SMALL);
        tableLayout.addComponent(table);
        
        PagedTableControls controls = table.createControls();
        controls.getItemsPerPageLabel().setValue("Items");
        controls.getBtnFirst().setCaption("First");
        controls.getBtnLast().setCaption("Last");
        controls.getBtnNext().setCaption("Next");
        controls.getBtnPrevious().setCaption("Previous");
        //controls.getPageLabel().setValue("Current:");
        tableLayout.addComponent(controls);
        
        // add custom container for lazy loading from DB
        List<ScalarIndexer> indexers = new ArrayList<>();
        var foiIdEncoder = ((AdminUI)UI.getCurrent()).getParentHub().getIdEncoders().getFoiIdEncoder();
        obsDataContainer = new LazyLoadingObsContainer(db, foiIdEncoder, dataStreamID, foiIDs, indexers);
        obsDataContainer.updateTimeRange(zoomTimeRange);
        table.setContainerDataSource(obsDataContainer);
        table.addListener(new PageChangeListener() {
            @Override
            public void pageChanged(PagedTableChangeEvent event)
            {
                obsDataContainer.onPageChanged();
            }
        });
        
        // add column names and indexers 
        DataComponent recordDef = dsInfo.getRecordStructure();
        addColumns(recordDef, recordDef, table, indexers);
        
        updateTable();
        return tableLayout;
    }
    
    
    @SuppressWarnings("serial")
    protected void addColumns(DataComponent recordDef, DataComponent component, Table table, List<ScalarIndexer> indexers)
    {
        // add FOI column
        if (component.getParent() == null)
        {
            table.addContainerProperty(FOI_COLUMN_ID, String.class, null, "FOI ID", null, null);
            
            table.addGeneratedColumn(FOI_COLUMN_ID, new ColumnGenerator() {
                @Override
                public Object generateCell(Table source, Object itemId, Object columnId)
                {
                    var foiId = source.getContainerProperty(itemId, columnId).getValue();
                    
                    if ("0000".equals(foiId))
                        return "None";
                    else if (csApiBaseUrl != null)
                        return new Label("<a href=\"" + csApiBaseUrl + "/fois?id=" + foiId + "\">" + foiId + "</a>", ContentMode.HTML);
                    else
                        return foiId;
                }
            });
        }
        
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
                        return dateFormat.formatIso(Double.parseDouble(value), 0);
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
                
                // stop here if there is a variable size array
                // TODO need to update indexer to support var size
                if (child instanceof DataArray && ((DataArray)child).isVariableSize())
                    break;
                
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
