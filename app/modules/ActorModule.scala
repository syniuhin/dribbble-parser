package modules

import actor.DribbbleActor
import com.google.inject.AbstractModule
import play.api.libs.concurrent.AkkaGuiceSupport

/**
  * Created by syniuhin with love <3.
  */
class ActorModule extends AbstractModule with AkkaGuiceSupport {
  def configure = {
    bindActor[DribbbleActor]("dribbble-actor")
  }
}
