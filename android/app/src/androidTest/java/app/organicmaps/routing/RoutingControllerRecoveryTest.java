package app.organicmaps.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Instrumentation;
import androidx.test.platform.app.InstrumentationRegistry;
import app.organicmaps.MwmApplication;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.Router;
import app.organicmaps.sdk.bookmarks.data.MapObject;
import app.organicmaps.sdk.routing.RouteMarkData;
import app.organicmaps.sdk.routing.RouteMarkType;
import app.organicmaps.sdk.routing.RoutingController;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class RoutingControllerRecoveryTest
{
  private final Instrumentation mInstrumentation = InstrumentationRegistry.getInstrumentation();
  private final MwmApplication mApplication = MwmApplication.from(mInstrumentation.getTargetContext());
  private RoutingController mController;
  private Router mOriginalRouter;

  @Before
  public void initializeCore() throws Exception
  {
    final CountDownLatch initialized = new CountDownLatch(1);
    onMain(() -> {
      try
      {
        if (!mApplication.initOrganicMaps(initialized::countDown))
          initialized.countDown();
      }
      catch (IOException e)
      {
        throw new UncheckedIOException(e);
      }
    });
    assertTrue("Core initialization did not complete", initialized.await(30, TimeUnit.SECONDS));
    onMain(() -> {
      mOriginalRouter = Router.getLastUsed();
      Framework.nativeCloseRouting();
      Framework.nativeDeleteSavedRoutePoints();
      mController = new RoutingController();
      mController.initialize(mApplication.getLocationHelper());
    });
  }

  @After
  public void clearRoute() throws Exception
  {
    if (mController == null)
      return;
    onMain(() -> {
      Framework.nativeCloseRouting();
      Framework.nativeDeleteSavedRoutePoints();
      Router.set(mOriginalRouter);
      RoutingController.get().initialize(mApplication.getLocationHelper());
    });
  }

  @Test
  public void idlePreparationReplacesOldPointsAndDeletesQueuedRecovery() throws Exception
  {
    final CountDownLatch completed = new CountDownLatch(1);
    final AtomicBoolean restored = new AtomicBoolean(true);
    onMain(() -> {
      saveRouteWithStop();
      mController.prepare(point("New start", 3), point("New finish", 4), Router.Ruler);
      assertTrue(mController.isPlanning());
      assertEquals(Router.Ruler, mController.getLastRouterType());
      final RouteMarkData[] points = Framework.nativeGetRoutePoints();
      assertEquals(2, points.length);
      assertEquals("New start", points[0].mTitle);
      assertEquals("New finish", points[1].mTitle);

      // Clear the preview without deleting recovery, so the load observes preparation's queued deletion.
      Framework.nativeCloseRouting();
      Framework.nativeSetRoutingLoadPointsListener(success -> {
        restored.set(success);
        completed.countDown();
      });
      Framework.nativeLoadRoutePoints();
    });
    assertTrue("Recovery load did not complete", completed.await(10, TimeUnit.SECONDS));
    assertFalse("Preparation must discard recovery even when its save was still queued", restored.get());
    onMain(() -> assertEquals(0, Framework.nativeGetRoutePoints().length));
  }

  @Test
  public void idlePreparationSupersedesPendingRecoveryWithAnEmptyPlan() throws Exception
  {
    final CountDownLatch completed = new CountDownLatch(1);
    final AtomicBoolean restored = new AtomicBoolean(true);
    onMain(() -> {
      saveRouteWithStop();
      Framework.nativeRemoveRoutePoints();
      Framework.nativeSetRoutingLoadPointsListener(success -> {
        restored.set(success);
        completed.countDown();
      });
      Framework.nativeLoadRoutePoints();
      mController.prepare(null, null, Router.Ruler);
      assertTrue(mController.isPlanning());
    });
    assertTrue("Recovery load did not complete", completed.await(10, TimeUnit.SECONDS));
    assertFalse("A pending load must not populate a newly requested empty plan", restored.get());
    onMain(() -> assertEquals(0, Framework.nativeGetRoutePoints().length));
  }

  private static void saveRouteWithStop()
  {
    assertTrue(Framework.nativeAddRoutePoint("Saved start", "", RouteMarkType.Start, false, 1, 1, false));
    assertTrue(Framework.nativeAddRoutePoint("Saved stop", "", RouteMarkType.Intermediate, false, 1.5, 1.5, false));
    assertTrue(Framework.nativeAddRoutePoint("Saved finish", "", RouteMarkType.Finish, false, 2, 2, false));
    Framework.nativeSaveRoutePoints();
  }

  private static MapObject point(String name, double coordinate)
  {
    return MapObject.createMapObject(MapObject.API_POINT, name, "", coordinate, coordinate);
  }

  private void onMain(Runnable action) throws Exception
  {
    final FutureTask<Void> task = new FutureTask<>(action, null);
    mInstrumentation.runOnMainSync(task);
    task.get();
  }
}
