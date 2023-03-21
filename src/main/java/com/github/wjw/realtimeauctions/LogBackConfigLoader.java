/*
 * author: @wjw
 * date:   2022年2月21日 下午5:20:03
 * note: 
 */
package com.github.wjw.realtimeauctions;

import java.io.File;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.joran.JoranConfigurator;
import ch.qos.logback.core.joran.spi.JoranException;
import ch.qos.logback.core.util.StatusPrinter;

/**
 * LogBack手动加载配置文件
 * 
 * @date 2023/03/07
 */
public class LogBackConfigLoader {
  private static AtomicBoolean loaded = new AtomicBoolean(false);

  private LogBackConfigLoader() {
  }

  /**
   * 从外部的文件系统中加载配置文件
   *
   * @param filename the external config file location
   * @throws JoranException the joran exception
   */
  public static boolean load(String filename) throws JoranException {
    if (false == loaded.getAndSet(true)) {
      File externalConfigFile = new File(filename);
      if (externalConfigFile.exists()) {
        load(externalConfigFile);
      } else {
        URL url = LogBackConfigLoader.class.getClassLoader().getResource(filename);
        load(url);
      }
      return true;
    } else {
      return false;
    }

  }

  /**
   * 从File中加载配置文件.
   *
   * @param file the file
   * @throws JoranException the joran exception
   */
  private static void load(File file) throws JoranException {
    LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();

    JoranConfigurator configurator = new JoranConfigurator();
    configurator.setContext(lc);
    lc.reset();
    configurator.doConfigure(file);
    StatusPrinter.printInCaseOfErrorsOrWarnings(lc);
  }

  /**
   * 从URL中加载配置文件.特别适合从classpath中来加载
   *
   * @param url the url
   * @throws JoranException the joran exception
   */
  private static void load(URL url) throws JoranException {
    LoggerContext lc = (LoggerContext) LoggerFactory.getILoggerFactory();

    JoranConfigurator configurator = new JoranConfigurator();
    configurator.setContext(lc);
    lc.reset();
    configurator.doConfigure(url);
    StatusPrinter.printInCaseOfErrorsOrWarnings(lc);
  }
}
