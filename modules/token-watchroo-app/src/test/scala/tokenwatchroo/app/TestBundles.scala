package tokenwatchroo.app

import tokenwatchroo.core.MessageBundles
import tokenwatchroo.providers.MessageResources

/** The message bundles embedded in the app test binary (#10), as `AppRuntime.start` loads them. */
object TestBundles {
  lazy val bundles: MessageBundles = MessageResources.load.fold(sys.error, identity)
}
