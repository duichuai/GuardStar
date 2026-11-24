package com.guardstar.app

import android.app.Application
import android.os.PowerManager
import com.guardstar.app.core.net.ApiClient
import com.guardstar.app.core.role.RoleStore
import com.guardstar.app.core.session.SessionStore
import com.guardstar.app.data.auth.AuthApi
import com.guardstar.app.data.location.LocationApi
import com.guardstar.app.data.location.LocationFlushWorker
import com.guardstar.app.data.location.LocationQueueStore
import com.guardstar.app.data.location.LocationRepository
import com.guardstar.app.data.relation.RelationApi

class GuardStarApp : Application() {

    lateinit var session: SessionStore
        private set
    lateinit var roleStore: RoleStore
        private set
    lateinit var authApi: AuthApi
        private set
    lateinit var relationApi: RelationApi
        private set
    lateinit var locationApi: LocationApi
        private set
    lateinit var locationRepository: LocationRepository
        private set

    override fun onCreate() {
        super.onCreate()
        // 高德地图隐私合规初始化：必须在任何地图 API 调用前执行。
        runCatching {
            com.amap.api.maps.MapsInitializer.updatePrivacyShow(this, true, true)
            com.amap.api.maps.MapsInitializer.updatePrivacyAgree(this, true)
        }
        session = SessionStore(this)
        roleStore = RoleStore(this)
        val api = ApiClient(session)
        authApi = AuthApi(api, session)
        relationApi = RelationApi(api, session)
        locationApi = LocationApi(api)
        val flushWakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "guardstar:flush")
            .apply { setReferenceCounted(false) }
        locationRepository = LocationRepository(
            LocationQueueStore(this),
            locationApi,
            session,
            flushWakeLock,
        )
        locationRepository.schedulePurge()
        LocationFlushWorker.enqueuePeriodic(this)
    }
}
