package pub.chara.hyperpadmeta.hook

abstract class BaseHook {
    var isInit: Boolean = false
    abstract fun init()
}