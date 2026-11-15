package ai.roadmarket.app

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Notes.init(this)
        Link.prepare(this)
        Market.init(this)
        Voice.init(this)
        Car.init(this)
        Navi.init(this)
        Link.init(this)
    }
}
