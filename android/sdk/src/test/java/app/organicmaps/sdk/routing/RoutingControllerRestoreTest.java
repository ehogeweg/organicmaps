package app.organicmaps.sdk.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mockStatic;

import app.organicmaps.sdk.bookmarks.data.MapObject;
import app.organicmaps.sdk.util.log.Logger;
import java.lang.reflect.Field;
import org.junit.Test;
import org.mockito.MockedStatic;

public class RoutingControllerRestoreTest
{
  @Test
  public void failedRestoreBuildKeepsTheLoadedItineraryInPlanning() throws ReflectiveOperationException
  {
    final int[] builds = {0};
    final RoutingController controller = new RoutingController() {
      @Override
      public MapObject getStartPoint()
      {
        return MapObject.createMapObject(MapObject.POI, "Start", "", 1, 1);
      }

      @Override
      public MapObject getEndPoint()
      {
        return MapObject.createMapObject(MapObject.POI, "Finish", "", 2, 2);
      }

      @Override
      public void prepare(MapObject start, MapObject finish)
      {
        fail("A restored itinerary must not be discarded by new-route preparation");
      }

      @Override
      void build()
      {
        ++builds[0];
      }
    };
    controller.attach(new RoutingController.Container() {
      @Override
      public void showRoutePlan(boolean show, Runnable completion)
      {
        assertTrue(show);
        assertNotNull(completion);
        completion.run();
      }
    });
    final Field listener = RoutingController.class.getDeclaredField("mRoutingLoadPointsListener");
    listener.setAccessible(true);
    final Field routingListener = RoutingController.class.getDeclaredField("mRoutingListener");
    routingListener.setAccessible(true);
    try (MockedStatic<Logger> ignored = mockStatic(Logger.class);
         MockedStatic<RoutingOptions> options = mockStatic(RoutingOptions.class))
    {
      ((RoutingLoadPointsListener) listener.get(controller)).onRoutePointsLoaded(true);
      ((RoutingListener) routingListener.get(controller)).onRoutingEvent(ResultCodes.ROUTE_NOT_FOUND, new String[0]);
    }
    assertTrue(controller.isPlanning());
    assertTrue(controller.isErrorEncountered());
    assertEquals(1, builds[0]);
  }
}
