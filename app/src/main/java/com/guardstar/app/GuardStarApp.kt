package com.guardstar.app

import android.app.Application
import com.guardstar.app.core.net.ApiClient
import com.guardstar.app.core.role.RoleStore
import com.guardstar.app.core.session.SessionStore
import com.guardstar.app.data.auth.AuthApi
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

    override fun onCreate() {
        super.onCreate()
        session = SessionStore(this)
        roleStore = RoleStore(this)
        val api = ApiClient(session)
        authApi = AuthApi(api, session)
        relationApi = RelationApi(api, session)
    }
}
