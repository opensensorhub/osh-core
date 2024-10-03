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
import java.time.ZoneOffset;
import org.jglue.fluentjson.JsonBuilderFactory;
import org.sensorhub.api.common.BigId;
import org.sensorhub.api.data.DataStreamInfo;
import org.sensorhub.api.data.IDataStreamInfo;
import org.sensorhub.api.system.SystemId;
import org.vast.ogc.geopose.Pose;
import org.vast.ogc.geopose.PoseImpl;
import org.vast.ogc.gml.IFeature;
import org.vast.sensorML.SMLHelper;
import org.vast.sensorML.SMLMetadataBuilders.CIResponsiblePartyBuilder;
import org.vast.sensorML.helper.CommonIdentifiers;
import org.vast.sensorML.sampling.SamplingPointXYZ;
import org.vast.swe.SWEConstants;
import org.vast.swe.SWEHelper;
import org.vast.swe.helper.GeoPosHelper;
import org.vast.util.TimeExtent;
import net.opengis.gml.v32.impl.GMLFactory;
import net.opengis.sensorml.v20.AbstractProcess;
import net.opengis.sensorml.v20.Deployment;


public class Saildrone
{
    public static final String PLATFORM_PROC_UID = "urn:x-saildrone:platform:explorer";
    static final String PLATFORM_FRAME_ID = "PLATFORM_FRAME";
    
    static SMLHelper sml = new SMLHelper();
    static GeoPosHelper swe = new GeoPosHelper();
    static GMLFactory gml = new GMLFactory(true);
    
    
    static void addResources() throws IOException
    {
        // add saildrone platform datasheet
        Api.addOrUpdateProcedure(createPlatformDatasheet(), true);
        
        // add datasheets of sensors used on by Saildrone
        Api.addOrUpdateProcedure(VectorNav.createVN200Datasheet(), true);
        Api.addOrUpdateProcedure(Rotronic.createHC2Datasheet(), true);
        Api.addOrUpdateProcedure(Vaisala.createPTB210Datasheet(), true);
        Api.addOrUpdateProcedure(Gill.createWindmasterDatasheet(), true);
        Api.addOrUpdateProcedure(Aanderaa.createOX4831Datasheet(), true);
        Api.addOrUpdateProcedure(Seabird.createSBE37Datasheet(), true);
        
        // add saildrone platform instance and on-board sensors
        var serials = new String[] {
            "1001",
            "1002"
        };
        var validTimes = new Instant[] {
            Instant.parse("2017-08-24T12:00:00Z"),
            Instant.parse("2022-04-11T18:00:00Z")
        };
        
        for (int i = 0; i < serials.length; i++)
        {
            var serialNum = serials[i];
            var validTime = validTimes[i];
            
            var saildrone1 = createPlatformInstance(serialNum, validTime);
            var s1Ins = createSensorInstance(saildrone1.getName() + " - INS/GPS", "nav", VectorNav.VN200_PROC_UID, serialNum, validTime);
            var s1Temp = createSensorInstance(saildrone1.getName() + " - Air Temp/Humidity Sensor", "temp", Rotronic.HC2_PROC_UID, serialNum, validTime);
            var s1Press = createSensorInstance(saildrone1.getName() + " - Air Pressure Sensor", "press", Vaisala.PTB210_PROC_UID, serialNum, validTime);
            var s1Wind = createSensorInstance(saildrone1.getName() + " - Wind Sensor", "wind", Gill.WINDMASTER_PROC_UID, serialNum, validTime);
            var s1Ctd = createSensorInstance(saildrone1.getName() + " - Water Temp/Salinity Sensor", "ctd", Seabird.SBE37_PROC_UID, serialNum, validTime);
            
            Api.addOrUpdateSystem(saildrone1, true);
            Api.addOrUpdateSubsystem(saildrone1.getUniqueIdentifier(), s1Ins, true);
            Api.addOrUpdateSubsystem(saildrone1.getUniqueIdentifier(), s1Temp, true);
            Api.addOrUpdateSubsystem(saildrone1.getUniqueIdentifier(), s1Press, true);
            Api.addOrUpdateSubsystem(saildrone1.getUniqueIdentifier(), s1Wind, true);
            Api.addOrUpdateSubsystem(saildrone1.getUniqueIdentifier(), s1Ctd, true);

            var cgSfId = Api.addOrUpdateSF(saildrone1.getUniqueIdentifier(), createPlatformSf(saildrone1), true);
            var atmosSfId = Api.addOrUpdateSF(saildrone1.getUniqueIdentifier(), createAtmosSf(saildrone1), true);
            var waterSfId = Api.addOrUpdateSF(saildrone1.getUniqueIdentifier(), createWaterSf(saildrone1), true);
            
            // add datastreams
            var navDs = createNavDataStream(saildrone1, s1Ins);
            var navDsId = Api.addOrUpdateDataStream(navDs, true);
            var tempDs = createTempDataStream(saildrone1, s1Temp);
            Api.addOrUpdateDataStream(tempDs, true);
            var humDs = createHumidityDataStream(saildrone1, s1Temp);
            Api.addOrUpdateDataStream(humDs, true);
            var pressDs = createPressureDataStream(saildrone1, s1Press);
            Api.addOrUpdateDataStream(pressDs, true);
            var windDs = createWindDataStream(saildrone1, s1Wind);
            Api.addOrUpdateDataStream(windDs, true);
            var ctdDs = createCtdDataStream(saildrone1, s1Ctd);
            Api.addOrUpdateDataStream(ctdDs, true);
            
            // add obs
            var result = JsonBuilderFactory.buildObject()
                .addObject("pos")
                    .add("lat", 37.596086)
                    .add("lon", -25.751654)
                    .add("alt", 0.0)
                    .end()
                .add("heading", 56.3)
                .add("course", 55.2)
                .add("sog", 8.6)
                .getJson();
            
            //Api.addOrUpdateObs(navDsId, cgSfId, Instant.parse("2023-09-25T00:00:00Z"), result, true);
            
            // add deployment
            var deploy1 = createDeployment("2025", TimeExtent.parse("2017-07-17T00:00:00Z/2017-09-29T00:00:00Z"));
            Api.addOrUpdateDeployment(deploy1, true);
        }
    }
    
    
    static AbstractProcess createPlatformDatasheet()
    {
        return sml.createPhysicalSystem()
            .definition(SWEConstants.DEF_PLATFORM)
            .uniqueID(PLATFORM_PROC_UID)
            .name("Saildrone Explorer")
            .description("The Saildrone Explorer is a 23-foot (7 m) vehicle powered\n"
                + "by wind and solar energy capable of extreme-duration\n"
                + "missions over 12 months in the open ocean, while\n"
                + "producing a minimal carbon footprint. Sailing at an\n"
                + "average speed up to three knots, the Explorer carries a\n"
                + "suite of scientific sensors for the collection of ocean data.")
            
            .addIdentifier(sml.identifiers.shortName("Saildrone Explorer"))
            .addIdentifier(sml.identifiers.modelNumber("Explorer"))
            
            .addCharacteristicList("physical", sml.createCharacteristicList()
                .label("Physical Characteristics")
                .add("weight", sml.characteristics.mass(750, "kg"))
                .add("length", sml.characteristics.length(7, "m")
                    .label("Hull Length"))
                .add("width", sml.characteristics.width(0.9, "m")
                    .label("Hull Width"))
                .add("height", sml.characteristics.height(5, "m")
                    .label("Wing Height"))
                .add("draft", sml.createQuantity()
                    .definition(SWEHelper.getDBpediaUri("Draft_(hull)"))
                    .label("Draft")
                    .uom("m")
                    .value(2))
             )
            .addCapabilityList("nav_capabilities", sml.capabilities.systemCapabilities()
                .add("avg_speed", sml.createQuantityRange()
                    .definition(SWEHelper.getPropertyUri("SpeedOverWater"))
                    .label("Average Speed")
                    .uomCode("[kn_i]")
                    .value(2, 3)
                )
                .add("max_speed", sml.createQuantity()
                    .definition(SWEHelper.getPropertyUri("SpeedOverWater"))
                    .label("Top Speed")
                    .uomCode("[kn_i]")
                    .value(8)
                )
                .add("endurance", sml.createQuantity()
                    .definition(SWEHelper.getDBpediaUri("Endurance"))
                    .description("Maximum mission duration")
                    .uom("d")
                    .value(365)
                )
             )
            .addCapabilityList("op_range", sml.capabilities.operatingProperties()
                .add("temperature", sml.conditions.temperatureRange(-50, 100, "Cel"))
                .add("humidity", sml.conditions.humidityRange(0, 100, "%"))
                .add("wind_speed", sml.createQuantity()
                    .definition(SWEHelper.getCfUri("wind_speed"))
                    .label("Max Wind Speed")
                    .uomCode("km/h")
                    .value(250)
                )
             )
            
            .addContact(getSaildroneContactInfo()
                .role(CommonIdentifiers.MANUFACTURER_DEF)
            )
            
            .addDocument(CommonIdentifiers.WEBPAGE_DEF, sml.createDocument()
                .name("Product Web Site")
                .description("Webpage with general info about Saildrone vehicles")
                .url("https://www.saildrone.com/technology/vehicles")
                .mediaType("text/html")
            )
            .addDocument(CommonIdentifiers.SPECSHEET_DEF, sml.createDocument()
                .name("Spec Sheet")
                .url("https://indd.adobe.com/view/cb870469-0058-408d-828a-9d83e49c8d79")
                .mediaType("application/pdf")
             )
            .addDocument(CommonIdentifiers.PHOTO_DEF, sml.createDocument()
                .name("Photo")
                .url("https://assets-global.website-files.com/5beaf972d32c0c1ce1fa1863/5ff24b92b64093621fc94b4b_A66I0480.jpg")
                .mediaType("image/jpg")
            )
            
            .addLocalReferenceFrame(sml.createSpatialFrame()
                .id("PLATFORM_FRAME")
                .label("Platform Frame")
                .description("Local reference frame attached to the Saildrone platform")
                .origin("Center of flotation of the USV")
                .addAxis("X", "Along the longitudinal axis of the symmetry of the hull, pointing forward")
                .addAxis("Y", "Orthogonal to both X and Z, forming a right handed frame")
                .addAxis("Z", "Along the axis of rotation of the sail, pointing down")
            )
            
            .build();
    }
    
    
    static AbstractProcess createPlatformInstance(String serialNum, Instant startTime)
    {
        return sml.createPhysicalSystem()
            .definition(SWEConstants.DEF_PLATFORM)
            .uniqueID("urn:x-osh:saildrone:" + serialNum)
            .name("Saildrone SD-" + serialNum)
            .typeOf(PLATFORM_PROC_UID)
            .addIdentifier(sml.identifiers.shortName("Saildrone Explorer USV"))
            .addIdentifier(sml.identifiers.serialNumber("SD-"+ serialNum))
            .addContact(getSaildroneContactInfo().role(CommonIdentifiers.OPERATOR_DEF))
            .validFrom(startTime.atOffset(ZoneOffset.UTC))
            .build();
    }
    
    
    static AbstractProcess createSensorInstance(String name, String type, String procUid, String serialNum, Instant startTime)
    {
        return sml.createPhysicalComponent()
            .definition(SWEConstants.DEF_SENSOR)
            .uniqueID("urn:x-osh:saildrone:" + serialNum + ":sensor:" + type)
            .name(name)
            .typeOf(procUid)
            .validFrom(startTime.atOffset(ZoneOffset.UTC))
            .build();
    }
    
    
    static CIResponsiblePartyBuilder getSaildroneContactInfo()
    {
        return sml.createContact()
            .organisationName("Saildrone, Inc.")
            .website("https://www.saildrone.com")
            .deliveryPoint("1050 W. Tower Ave.")
            .city("Alameda")
            .postalCode("94501")
            .administrativeArea("CA")
            .country("USA")
            .email("info@saildrone.com");
    }
    
    
    static IFeature createPlatformSf(AbstractProcess sys)
    {
        var sysUid = sys.getUniqueIdentifier();
        
        var sf = new SamplingPointXYZ();
        sf.setUniqueIdentifier(sysUid + ":gps-sf");
        sf.setName(sys.getName() + " - GPS Antenna");
        sf.setSampledFeature("Saildrone Platform", sys.getUniqueIdentifier());
        sf.setPose(Pose.create()
            .referenceFrame(sys.getUniqueIdentifier() + "#" + PLATFORM_FRAME_ID)
            .xyzPos(1.2, 0, 0.0)
            .build());
        return sf;
    }
    
    
    static IFeature createAtmosSf(AbstractProcess sys)
    {
        var sysUid = sys.getUniqueIdentifier();
        
        var sf = new SamplingPointXYZ();
        sf.setUniqueIdentifier(sysUid + ":atm-sf");
        sf.setName(sys.getName() + " - Atmosphere Sampling Point");
        sf.setSampledFeature("Earth Atmosphere", SWEHelper.getDBpediaUri("Atmosphere_of_Earth"));
        sf.setPose(Pose.create()
            .referenceFrame(sys.getUniqueIdentifier() + "#" + PLATFORM_FRAME_ID)
            .xyzPos(0, 0, 2.5)
            .build());
        return sf;
    }
    
    
    static IFeature createWaterSf(AbstractProcess sys)
    {
        var sysUid = sys.getUniqueIdentifier();
        
        var sf = new SamplingPointXYZ();
        sf.setUniqueIdentifier(sysUid + ":water-sf");
        sf.setName(sys.getName() + " - Water Sampling Point");
        sf.setSampledFeature("Seawater", SWEHelper.getDBpediaUri("Seawater"));
        sf.setPose(Pose.create()
            .referenceFrame(sys.getUniqueIdentifier() + "#" + PLATFORM_FRAME_ID)
            .xyzPos(0, 0, -0.53)
            .build());
        return sf;
    }
    
    
    static IDataStreamInfo createNavDataStream(AbstractProcess platform, AbstractProcess sensor)
    {
        var navHelper = new GeoPosHelper();
        var platformFrameUri = platform.getUniqueIdentifier() + "#" + PLATFORM_FRAME_ID;
        
        return new DataStreamInfo.Builder()
            .withSystem(new SystemId(BigId.NONE, sensor.getUniqueIdentifier()))
            .withName(platform.getName() + " - Navigation Data")
            .withRecordEncoding(sml.newTextEncoding())
            .withRecordDescription(sml.createRecord()
                .name("nav")
                .label("USV Navigation Data")
                .addField("time", sml.createTime()
                    .asPhenomenonTimeIsoUTC()
                )
                .addField("pos", navHelper.createLocationVectorLLA()
                    .label("Geographic Location")
                    .localFrame(platformFrameUri)
                )
                .addField("heading", navHelper.createQuantity()
                    .definition(GeoPosHelper.DEF_HEADING_TRUE)
                    .refFrame(SWEConstants.REF_FRAME_NED)
                    .axisId("Z")
                    .label("Heading")
                    .description("Heading angle from true north, measured clockwise")
                    .uomCode("deg")
                )
                .addField("course", navHelper.createQuantity()
                    .definition(SWEHelper.getPropertyUri("CourseAngle"))
                    .refFrame(SWEConstants.REF_FRAME_NED)
                    .axisId("Z")
                    .label("Course")
                    .description("Course angle from true north, measured clockwise")
                    .uomCode("deg")
                )
                .addField("sog", navHelper.createQuantity()
                    .definition(SWEHelper.getPropertyUri("SpeedOverGround"))
                    .label("Speed over Ground")
                    .uomCode("[kn_i]")
                )
                .build()
            )
            .build();
    }
    
    
    static IDataStreamInfo createTempDataStream(AbstractProcess platform, AbstractProcess sensor)
    {
        return new DataStreamInfo.Builder()
            .withSystem(new SystemId(BigId.NONE, sensor.getUniqueIdentifier()))
            .withName(platform.getName() + " - Air Temperature")
            .withRecordEncoding(sml.newTextEncoding())
            .withRecordDescription(sml.createQuantity()
                .name("temp")
                .definition(SWEHelper.getCfUri("air_temperature"))
                .label("Air Temperature")
                .uomCode("Cel")
                .build()
            )
            .build();
    }
    
    
    static IDataStreamInfo createHumidityDataStream(AbstractProcess platform, AbstractProcess sensor)
    {
        return new DataStreamInfo.Builder()
            .withSystem(new SystemId(BigId.NONE, sensor.getUniqueIdentifier()))
            .withName(platform.getName() + " - Humidity")
            .withRecordEncoding(sml.newTextEncoding())
            .withRecordDescription(sml.createQuantity()
                .name("hum")
                .definition(SWEHelper.getCfUri("relative_humidity"))
                .label("Relative Humidity")
                .uomCode("%")
                .build()
            )
            .build();
    }
    
    
    static IDataStreamInfo createPressureDataStream(AbstractProcess platform, AbstractProcess sensor)
    {
        return new DataStreamInfo.Builder()
            .withSystem(new SystemId(BigId.NONE, sensor.getUniqueIdentifier()))
            .withName(platform.getName() + " - Atmospheric Pressure")
            .withRecordEncoding(sml.newTextEncoding())
            .withRecordDescription(sml.createQuantity()
                .name("press")
                .definition(SWEHelper.getCfUri("air_pressure"))
                .label("Air Pressure")
                .uomCode("hPa")
                .build()
            )
            .build();
    }
    
    
    static IDataStreamInfo createWindDataStream(AbstractProcess platform, AbstractProcess sensor)
    {
        return new DataStreamInfo.Builder()
            .withSystem(new SystemId(BigId.NONE, sensor.getUniqueIdentifier()))
            .withName(platform.getName() + " - Wind")
            .withRecordEncoding(sml.newTextEncoding())
            .withRecordDescription(sml.createRecord()
                .name("wind")
                .label("Wind Measurements")
                .addField("wind_speed", sml.createQuantity()
                    .definition(SWEHelper.getCfUri("wind_speed"))
                    .label("Wind Speed")
                    .uomCode("km/h")
                )
                .addField("wind_dir", sml.createQuantity()
                    .definition(SWEHelper.getCfUri("wind_from_direction"))
                    .label("Wind Direction")
                    .description("Direction the wind is coming from, measured clockwise from north")
                    .refFrame(SWEConstants.REF_FRAME_NED)
                    .axisId("Z")
                    .uomCode("deg")
                )
                .build()
            )
            .build();
    }
    
    
    static IDataStreamInfo createCtdDataStream(AbstractProcess platform, AbstractProcess sensor)
    {
        return new DataStreamInfo.Builder()
            .withSystem(new SystemId(BigId.NONE, sensor.getUniqueIdentifier()))
            .withName(platform.getName() + " - Water Temp/Salinity")
            .withRecordEncoding(sml.newTextEncoding())
            .withRecordDescription(sml.createRecord()
                .name("water")
                .label("Water Measurements")
                .addField("temp", sml.createQuantity()
                    .definition(SWEHelper.getCfUri("water_temperature"))
                    .label("Water Temperature")
                    .uomCode("Cel")
                )
                .addField("conductivity", sml.createQuantity()
                    .definition(SWEHelper.getCfUri("sea_water_electrical_conductivity"))
                    .label("Water Conductivity")
                    .uomCode("S.m-1")
                )
                .build()
            )
            .build();
    }
    
    
    static Deployment createDeployment(String num, TimeExtent validTime)
    {
        var fac = new GMLFactory();
        var geom = fac.newPolygon();
        
        //geom.getExterior().setPosList(null);
        
        return sml.createDeployment()
            .uniqueID("urn:x-osh:saildrone:mission:" + num)
            .name("Saildrone - 2017 Arctic Mission")
            .description("In July 2017, three saildrones were launched from Dutch Harbor, Alaska, in partnership with NOAA Research...")
            .addContact(getOperatorContactInfo().role(CommonIdentifiers.OPERATOR_DEF))
            .validTimePeriod(
                validTime.begin().atOffset(ZoneOffset.UTC),
                validTime.end().atOffset(ZoneOffset.UTC))
            .location(null)
            .build();
    }
    
    
    static CIResponsiblePartyBuilder getOperatorContactInfo()
    {
        return sml.createContact()
            .role(SWEHelper.getPropertyUri("Operator"))
            .organisationName("NOAA Pacific Marine Environmental Laboratory")
            .website("https://pmel.noaa.gov")
            .deliveryPoint("7600 Sand Point Way NE")
            .city("Seattle")
            .postalCode("98115")
            .administrativeArea("WA")
            .country("USA");
    }

}
