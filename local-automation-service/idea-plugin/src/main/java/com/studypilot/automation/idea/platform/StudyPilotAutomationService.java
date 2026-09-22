package com.studypilot.automation.idea.platform;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.studypilot.automation.idea.dispatch.IdeActionDispatcher;
import com.studypilot.automation.idea.protocol.NonceLedger;
import com.studypilot.automation.idea.protocol.PluginProtocol;

/**
 * Application-scoped lifecycle for the plugin's private bridge.
 *
 * The bridge is started at most once per IDE session and stopped when the application is
 * disposed. A failed start is logged without secrets and leaves the bridge stopped, so the
 * local automation service fails closed rather than silently degrading.
 */
public final class StudyPilotAutomationService implements Disposable {

  private static final Logger LOG = Logger.getInstance(StudyPilotAutomationService.class);
  private static final Object LOCK = new Object();
  private static boolean startAttempted = false;

  private PluginSocketServer server;

  /** Starts the bridge once per IDE session. Safe to call from any startup hook. */
  public static void startOnce() {
    synchronized (LOCK) {
      if (startAttempted) {
        return;
      }
      startAttempted = true;
    }
    StudyPilotAutomationService service =
        ApplicationManager.getApplication().getService(StudyPilotAutomationService.class);
    if (service != null) {
      service.start();
    }
  }

  /** Idempotent start. Never throws; a failure leaves the bridge stopped. */
  public synchronized void start() {
    if (server != null && server.isRunning()) {
      return;
    }
    try {
      PluginConfig config = PluginConfig.load(PluginConfig.defaultConfigFile(), System.getenv());
      PluginProtocol protocol = new PluginProtocol(config.secret);
      IdeActionDispatcher dispatcher =
          new IdeActionDispatcher(
              protocol,
              config.registry,
              new NonceLedger(config.ledgerPath),
              new IdeaPlatformOperations(),
              new IdeUiExecutor(),
              System::currentTimeMillis,
              config.uiTimeoutMs);
      PluginSocketServer candidate =
          new PluginSocketServer(config, protocol, dispatcher, message -> LOG.info("StudyPilot: " + message));
      candidate.start();
      server = candidate;
    } catch (Exception e) {
      // No secret, path or stack detail is logged.
      LOG.warn("StudyPilot automation bridge did not start");
      server = null;
    }
  }

  public synchronized boolean isRunning() {
    return server != null && server.isRunning();
  }

  @Override
  public synchronized void dispose() {
    if (server != null) {
      server.stop();
      server = null;
    }
  }
}
