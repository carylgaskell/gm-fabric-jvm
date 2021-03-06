package com.deciphernow.server


/*
    Copyright 2017 Decipher Technology Studios LLC

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
*/

import java.net._

import com.deciphernow.server.rest.GMFabricRestServer
import com.deciphernow.server.support.SupportUtils
import com.deciphernow.server.thrift.GMFabricThriftServer
import com.twitter.app.App
import com.twitter.finagle._
import com.twitter.logging.Logger
import com.twitter.util.{Future, Time}
import com.deciphernow.server.{config => configuration}

/**
  * Created by ghershfield on 4/19/16.
  */
abstract class GMFabricServer extends App {

  val log = Logger.get(getClass)
  var restServer : Option[GMFabricRestServer] = None
  var thriftServer : Option[GMFabricThriftServer] = None

  def rest(): Option[RestServer]
  def thrift(): Option[ThriftServer]

  onExit {
    log.ifWarning("Shutting server down")
    if (restServer!=None) { restServer map (_.close()) }
    if (thriftServer!=None) { thriftServer map (_.close()) }
  }


  /**
    *
    */
  final def main(): Unit = {

    log.ifDebug("Main starting up ... ")

    useIpAddressResolution = configuration.ipAddress.enableIpAddressResolution.get.fold(false)(_ => true)
    networkInterfaceName = configuration.ipAddress.useNetworkInterfaceName.get.fold("")(definedInterfaceName => definedInterfaceName)

    sys.addShutdownHook(close(Time.fromSeconds(2)))

    thrift match {
      case Some(_) =>
        log.ifDebug("creating thrift server.")
        thriftServer = Option(new GMFabricThriftServer(thrift.get.filters, thrift.get.service))
        thriftServer.foreach(_ => {
          thriftServer.get.main(Array())
          log.ifInfo(s"thrift server started on port ${configuration.thrift.port()}")
          log.ifDebug("server label is -> " + SupportUtils.createServerLabel("thrift",SupportUtils.isSsl))
          announce(thriftServer.get.getServer,SupportUtils.createServerLabel("thrift",SupportUtils.isSsl))
        })
      case _ => log.info("No thrift server defined.")
    }

    rest match {
      case Some(_) =>
        log.ifDebug("creating restful server.")
        restServer = Option(new GMFabricRestServer(rest.get.filters, rest.get.controllers))
        restServer.foreach( _ =>
          if (configuration.zk.announcementPoint.equals("") || configuration.zk.zookeeperConnection.equals("")) {
            restServer.get.main(Array())
          }
          else {
            restServer.get.main(
              Array(configureAnnouncement("-http.announce", "http"), configureAnnouncement("-https.announce", "https"), configureAnnouncement("-admin.announce", "admin"))
            )
          }
        )
      case _ => log.error("No rest server defined. All services will shutdown.")
    }

  }

  var useIpAddressResolution : Boolean = false
  var networkInterfaceName : String = ""
  private[this] var announcements = List.empty[Future[Announcement]]


  /**
    *
    * @param server
    * @param scheme
    */
  private def announce(server: Option[ListeningServer], scheme: String): Unit = {
    // Make the service known to the world
    server foreach  { server =>
      if (!configuration.zk.zookeeperConnection().isEmpty && !configuration.zk.announcementPoint().isEmpty) {
        log.ifInfo(s"Announcing ${scheme} to Zookeeper at ${configuration.zk.announcementPoint()}")
        val addr = s"zk!${configuration.zk.zookeeperConnection()}!${configuration.zk.announcementPoint()}/${scheme}!0"
        if (useIpAddressResolution) {
          val public = if (networkInterfaceName.length>0) {
            getLocalAddressByName(server.boundAddress,networkInterfaceName)
          }
          else {
            locateAddress(server.boundAddress)
          }
          val ann = Announcer.announce(public.asInstanceOf[InetSocketAddress], addr)
          announcements ::= ann
          ann
        }
        else {
          server.announce(addr)
        }
      }
      else {
        log.ifWarning(s"Zookeeper announcement point not configured!  Not announcing ${scheme} services to Zookeeper!")
      }
    }
  }

  /**
    *
    * @param twitterAttribute
    * @param scheme
    * @return
    */
  private def configureAnnouncement(twitterAttribute: String, scheme: String) : String = {
    var announcement = ""
    if (!configuration.zk.zookeeperConnection().isEmpty && !configuration.zk.announcementPoint().isEmpty) {
      announcement = twitterAttribute + "=zk!" + configuration.zk.zookeeperConnection.get.get +
              "!" + configuration.zk.announcementPoint.get.get + "/" + scheme + "!0"
    }
    else {
      log.ifWarning(s"Zookeeper announcement point not configured!  Not announcing ${scheme} services to Zookeeper!")
    }
    log.ifDebug(announcement)
    announcement
  }

  /**
    * Convert the byte array representation of the IPv4 address to a string.
    *
    * @param rawBytes
    * @return
    */
  def convertIpAddress(rawBytes: Array[Byte]) : String = {
    rawBytes.map(n => n & 0xFF).mkString(".")
  }

  /**
    *
    * @param bound
    * @param interfaceName
    * @return
    */
  def getLocalAddressByName(bound: SocketAddress, interfaceName: String) : SocketAddress = {
    val port = bound.asInstanceOf[InetSocketAddress].getPort
    var addressOfInterest = None: Option[SocketAddress]
    try {
      val interface = NetworkInterface.getByName(interfaceName)
      val addresses = interface.getInetAddresses
      while (addresses.hasMoreElements) {
        var addr = addresses.nextElement()
        if (addr.isInstanceOf[Inet4Address] && !addr.isLoopbackAddress) {
          addressOfInterest = Some(new InetSocketAddress(convertIpAddress(addr.getAddress), port))
        }
      }
    }
    catch {
      case _ : Exception => None
    }
    addressOfInterest match {
      case Some(_) => addressOfInterest.get
      case None => new InetSocketAddress(InetAddress.getLoopbackAddress,port)
    }
  }

  /**
    * This traverses all available network interfaces and identifies an IPv4 interface that
    * is NOT a loopback and returns the IP Address of it.
    *
    * @param bound
    * @return
    */
  def locateAddress(bound: SocketAddress) : SocketAddress = {
    val port = bound.asInstanceOf[InetSocketAddress].getPort
    val interfaces = NetworkInterface.getNetworkInterfaces
    var addressOfInterest = None: Option[SocketAddress]
    while (interfaces.hasMoreElements) {
      var addresses = interfaces.nextElement().getInetAddresses
      while (addresses.hasMoreElements) {
        var addr = addresses.nextElement()
        if (addr.isInstanceOf[Inet4Address] && !addr.isLoopbackAddress) {
          addressOfInterest = Some(new InetSocketAddress(convertIpAddress(addr.getAddress), port))
        }
      }
    }
    addressOfInterest match {
      case Some(_) => addressOfInterest.get
      case None => new InetSocketAddress(InetAddress.getLoopbackAddress,port)
    }
  }

}
