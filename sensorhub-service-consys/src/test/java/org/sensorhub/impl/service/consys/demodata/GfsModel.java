/***************************** BEGIN LICENSE BLOCK ***************************

The contents of this file are subject to the Mozilla Public License, v. 2.0.
If a copy of the MPL was not distributed with this file, You can obtain one
at http://mozilla.org/MPL/2.0/.

Software distributed under the License is distributed on an "AS IS" basis,
WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
for the specific language governing rights and limitations under the License.
 
Copyright (C) 2023 Sensia Software LLC. All Rights Reserved.
 
******************************* END LICENSE BLOCK ***************************/

package org.sensorhub.impl.service.consys.demodata;

import java.io.IOException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Collections;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.data.DataStreamInfo;
import org.sensorhub.api.data.IDataStreamInfo;
import org.sensorhub.api.feature.FeatureId;
import org.vast.sensorML.SMLHelper;
import org.vast.sensorML.SMLMetadataBuilders.CIResponsiblePartyBuilder;
import org.vast.sensorML.helper.CommonIdentifiers;
import org.vast.swe.SWEConstants;
import org.vast.swe.SWEHelper;
import org.vast.swe.helper.GeoPosHelper;
import org.vast.swe.helper.RasterHelper;
import net.opengis.sensorml.v20.AbstractProcess;


public class GfsModel
{
    public static final String GFS_PROC_UID = "urn:x-noaa:forecast:gfs:v16.3";
    
    static SMLHelper sml = new SMLHelper();
    static GeoPosHelper swe = new GeoPosHelper();
    
    
    static void addResources() throws IOException
    {
        // add GFS model specs
        Api.addOrUpdateProcedure(createGFSModelSpecs(), true);
        
        // add model instance
        var modelSys = createModelInstance(Instant.parse("2022-11-29T00:00:00Z"));
        Api.addOrUpdateSystem(modelSys, true);
        Api.addOrUpdateDataStream(createForecastDataStream(modelSys), true);
    }
    
    
    static AbstractProcess createGFSModelSpecs()
    {
        return sml.createSimpleProcess()
            .definition(SWEConstants.DEF_SYSTEM)
            .assetType(SWEConstants.ASSET_TYPE_SIMULATION)
            .uniqueID(GFS_PROC_UID)
            .name("Global Forecast System (GFS)")
            .description("The Global Forecast System (GFS) is a global weather forecast model developed "
                + "at the National Centers for Environmental Prediction (NCEP). It generates data for "
                + "dozens of atmospheric and land-soil variables, including temperatures, winds, precipitation, "
                + "soil moisture, and atmospheric ozone concentration. The system couples four "
                + "separate models (atmosphere, ocean model, land/soil model, and sea ice) that "
                + "work together to accurately depict weather conditions. The model is constantly evolving, "
                + "and regularly adjusted to improve performance and forecast accuracy.")
            
            .addIdentifier(sml.identifiers.shortName("GFS FV3"))
            .addIdentifier(sml.identifiers.longName("Global Forecast System (GFS), FV3 Variant"))
            .addIdentifier(sml.identifiers.author("NCEP"))
            .addIdentifier(sml.identifiers.softwareVersion("16.3"))
            .addClassifier(sml.classifiers.sensorType("Weather Forecast"))
            
            .addInput("air_temp", sml.createObservableProperty()
                .definition(SWEHelper.getCfUri("air_temperature"))
                .label("Air Temperature")
                .build()
            )
            .addInput("air_press", sml.createObservableProperty()
                .definition(SWEHelper.getCfUri("air_pressure"))
                .label("Air Pressure")
                .build()
            )
            .addInput("precip", sml.createObservableProperty()
                .definition(SWEHelper.getCfUri("precipitation_amount"))
                .label("Precipitation Amount")
                .build()
            )
            .addInput("humid", sml.createObservableProperty()
                .definition(SWEHelper.getCfUri("relative_humidity"))
                .label("Relative Humidity")
                .build()
            )
            .addInput("wind_speed", sml.createObservableProperty()
                .definition(SWEHelper.getCfUri("wind_speed"))
                .label("Wind Speed")
                .build()
            )
            .addInput("cloud", sml.createObservableProperty()
                .definition("http://mmisw.org/ont/ioos/parameter/cloud_cover")
                .label("Cloud Cover")
                .build()
            )
            .addInput("radiance", sml.createObservableProperty()
                .definition(SWEHelper.getCfUri("solar_irradiance"))
                .label("Solar Irradiance")
                .build()
            )
            .addInput("soil_temp", sml.createObservableProperty()
                .definition(SWEHelper.getCfUri("soil_temperature"))
                .label("Soil Temperature")
                .build()
            )
            .addInput("soil_moist", sml.createObservableProperty()
                .definition(SWEHelper.getCfUri("soil_moisture_content"))
                .label("Soil Moisture")
                .build()
            )
            
            .validFrom(OffsetDateTime.parse("2022-11-29T00:00:00Z"))
            
            .addContact(getNcepContactInfo()
                .role(CommonIdentifiers.AUTHOR_DEF)
            )
            
            .addDocument(CommonIdentifiers.WEBPAGE_DEF, sml.createDocument()
                .name("GFS Documentation Page")
                .description("GFS documentation page at NCEP")
                .url("https://www.emc.ncep.noaa.gov/emc/pages/numerical_forecast_systems/gfs/documentation.php")
                .mediaType("text/html")
            )
            .addDocument(CommonIdentifiers.WEBPAGE_DEF, sml.createDocument()
                .name("GFS Wikipedia Page")
                .url("https://en.wikipedia.org/wiki/Global_Forecast_System")
                .mediaType("text/html")
            )
            .build();
    }
    
    
    static AbstractProcess createModelInstance(Instant startTime)
    {
        return sml.createSimpleProcess()
            .definition(SWEConstants.DEF_SYSTEM)
            .assetType(SWEConstants.ASSET_TYPE_SIMULATION)
            .uniqueID("urn:x-noaa:forecast:gfs:ncep")
            .name("GFS Weather Forecast at NCEP")
            .description("Instance of GFS model running at NCEP. This instance outputs data with a base "
                + "horizontal resolution of 18 miles (28 kilometers) between grid points. Temporal resolution "
                + "covers analysis and forecasts out to 16 days. Horizontal resolution drops to 44 miles "
                + "(70 kilometers) between grid points for forecasts between one week and two weeks.")
            .typeOf(GFS_PROC_UID)
            .addIdentifier(sml.identifiers.shortName("GFS Weather Forecast"))
            .addCapabilityList("model_caps", sml.capabilities.systemCapabilities()
                .label("Model Capabilities")
                .add("run_period", sml.createTime()
                    .definition("http://sensorml.com/ont/x-swe/property/ForecastRunPeriod")
                    .label("Run Period")
                    .uomCode("h")
                    .value(6.0)
                )
                .add("forecast_horizon", sml.createTime()
                    .definition("http://sensorml.com/ont/x-swe/property/ForecastTimeHorizon")
                    .label("Temporal Horizon")
                    .uomCode("h")
                    .value(384.0)
                )
                .add("horiz_res_ang", sml.createQuantity()
                    .definition(SWEHelper.getDBpediaUri("Spatial_resolution"))
                    .label("Horizontal Resolution")
                    .uomCode("deg")
                    .value(0.25)
                )
                .add("horiz_res_dist", sml.createQuantity()
                    .definition(SWEHelper.getDBpediaUri("Spatial_resolution"))
                    .label("Horizontal Resolution")
                    .description("Horizontal resolution at the equator")
                    .uomCode("km")
                    .value(28)
                )
                .add("vert_levels", sml.createCount()
                    .definition("http://sensorml.com/ont/x-meteo/def/ForecastVerticalLevels")
                    .label("Vertical Levels")
                    .value(127)
                )
            )
            .addContact(getNcepContactInfo()
                .role(CommonIdentifiers.OPERATOR_DEF))
            .validFrom(startTime.atOffset(ZoneOffset.UTC))
            .build();
    }
    
    
    static Collection<AbstractProcess> getModelInstances()
    {
        return Collections.emptyList();
    }
    
    
    static CIResponsiblePartyBuilder getNcepContactInfo()
    {
        return sml.createContact()
            .organisationName("NCEP Central Operations, ")
            .website("https://www.nco.ncep.noaa.gov")
            .deliveryPoint("5830 University Research Court")
            .city("College Park")
            .postalCode("20740")
            .administrativeArea("MD")
            .country("USA")
            .phone("+1 800 874-8647")
            .email("ncep.webmaster@noaa.gov");
    }
    
    
    static IDataStreamInfo createForecastDataStream(AbstractProcess sys)
    {
        return new DataStreamInfo.Builder()
            .withSystem(new FeatureId(BigId.NONE, sys.getUniqueIdentifier()))
            .withName(sys.getName() + " - PGRB2 0.25deg Dataset")
            .withDescription("3D forecast product with most commonly used parameters, 0.25 degrees resolution")
            .withRecordEncoding(sml.newTextEncoding())
            .withRecordDescription(sml.createRecord()
                .name("forecast_data")
                .label("Forecast Output")
                .addField("time", sml.createTime()
                    .asPhenomenonTimeIsoUTC()
                    .label("Forecasted Time")
                )
                .addField("grid_width", sml.createCount()
                    .definition(RasterHelper.DEF_RASTER_WIDTH)
                    .label("Grid Width")
                    .description("Size of grid along the longitude dimension")
                    .id("GRID_WIDTH"))
                .addField("grid_height", sml.createCount()
                    .definition(RasterHelper.DEF_RASTER_HEIGHT)
                    .label("Grid Height")
                    .description("Size of grid along the latitude dimension")
                    .id("GRID_HEIGHT"))
                .addField("grid_depth", sml.createCount()
                    .definition(SWEHelper.getPropertyUri("GridDepth"))
                    .label("Grid Depth")
                    .description("Size of grid along the vertical dimension")
                    .id("GRID_DEPTH"))
                .addField("range_data", sml.createArray()
                    .withVariableSize("GRID_DEPTH")
                    .withElement("level", sml.createArray()
                        .withVariableSize("GRID_HEIGHT")
                        .withElement("row", sml.createArray()
                            .withVariableSize("GRID_WIDTH")
                            .withElement("row", sml.createRecord()
                                .addField("HGT", sml.createQuantity()
                                    .definition(SWEHelper.getCfUri("geopotential_height"))
                                    .label("Geopotential Height")
                                    .uomCode("m")
                                )
                                .addField("TMP", sml.createQuantity()
                                    .definition(SWEHelper.getCfUri("air_temperature"))
                                    .label("Air Temperature")
                                    .uomCode("K")
                                )
                                .addField("RH", sml.createQuantity()
                                    .definition(SWEHelper.getCfUri("relative_humidity"))
                                    .label("Relative Humidity")
                                    .uomCode("%")
                                )
                                .addField("TCDC", sml.createQuantity()
                                    .definition(SWEHelper.getCfUri("cloud_area_fraction_in_atmosphere_layer"))
                                    .label("Cloud Cover")
                                    .uomCode("%")
                                )
                                .addField("UGRD", sml.createQuantity()
                                    .definition(SWEHelper.getCfUri("eastward_wind"))
                                    .label("Eastward Wind Speed")
                                    .description("U component of wind (towards east)")
                                    .uomCode("m/s")
                                )
                                .addField("VGRD", sml.createQuantity()
                                    .definition(SWEHelper.getCfUri("northward_wind"))
                                    .label("Northward Wind Speed")
                                    .description("V component of wind (towards north)")
                                    .uomCode("m/s")
                                )
                                .addField("ABSV", sml.createQuantity()
                                    .definition(SWEHelper.getCfUri("atmosphere_upward_absolute_vorticity"))
                                    .label("Absolute Vorticity ")
                                    .uomCode("1/s")
                                )
                                .addField("ICMR", sml.createQuantity()
                                    .definition(SWEHelper.getCfUri("cloud_ice_mixing_ratio"))
                                    .label("Ice Mixing Ratio")
                                    .uomCode("1")
                                )
                                .addField("RWMR", sml.createQuantity()
                                    .definition(SWEHelper.getCfUri("cloud_liquid_water_mixing_ratio"))
                                    .label("Rain Mixing Ratio")
                                    .uomCode("1")
                                )
                                .addField("SNMR", sml.createQuantity()
                                    .definition(SWEHelper.getCfUri("mass_fraction_of_snow_in_air"))
                                    .label("Snow Mixing Ratio")
                                    .uomCode("1")
                                )
                                .addField("O3MR", sml.createQuantity()
                                    .definition(SWEHelper.getCfUri("mass_fraction_of_ozone_in_air"))
                                    .label("Ozone Mixing Ratio")
                                    .uomCode("1")
                                )
                            )
                        )
                    )
                )
                .build())
            .build();
    }

}
