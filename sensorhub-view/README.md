# Landing Page Service

A simple landing page service to act as a bridge between users and OpenSensorHub. It's main goal is to guide users with different roles to the correct pages. 


## How to configure the Landing Service

To configure the landing service you need to ensure that the landing service is included in the project wide build.gradle and the settings.gradle.



### Adding the HttpServerWrapper to config.json

Update the config.json to point to the HttpServerWrapper, by modifying the `"moduleClass"` to point to "org.sensorhub.impl.service.landing.HttpServerWrapper".
```
{
"objClass": "org.sensorhub.impl.service.HttpServerConfig",
"httpPort": 8282,
"httpsPort": 0,
"staticDocsRootUrl": "/",
"staticDocsRootDir": "web",
"servletsRootUrl": "/sensorhub",
"authMethod": "BASIC",
"keyStorePath": ".keystore/ssl_keys",
"keyAlias": "jetty",
"trustStorePath": ".keystore/ssl_trust",
"enableCORS": true,
"id": "5cb05c9c-9e08-4fa1-8731-ffaa5846bdc1",
"autoStart": true,
"moduleClass": "org.sensorhub.impl.service.landing.HttpServerWrapper",
"name": "HTTP Server"
},
```

