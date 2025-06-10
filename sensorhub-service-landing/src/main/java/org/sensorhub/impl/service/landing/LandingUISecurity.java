package org.sensorhub.impl.service.landing;

import org.sensorhub.impl.module.ModuleSecurity;
import org.vast.util.Asserts;

/**
 * Defines the security permissions available through the landing page service
 * @author Kalyn Stricklin
 * @since February 2025
 */
public class LandingUISecurity extends ModuleSecurity {

    LandingService landingService;

    public LandingUISecurity(LandingService landingService, boolean enable) {

        super(landingService, "landing", enable);

        this.landingService = Asserts.checkNotNull(landingService, LandingService.class);

        //register permission tree
        landingService.getParentHub().getSecurityManager().registerModulePermissions(rootPerm);
    }

    @Override
    protected boolean isAccessControlEnabled()
    {
        var httpServer = ((LandingService)module).getHttpServer();
        return super.isAccessControlEnabled() && httpServer.isAuthEnabled();
    }

}
