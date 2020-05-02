/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tuweni.discovery
/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.tuweni.crypto.SECP256K1
import org.apache.tuweni.devp2p.EthereumNodeRecord
import org.slf4j.LoggerFactory
import org.xbill.DNS.DClass
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Record
import org.xbill.DNS.Section
import org.xbill.DNS.SimpleResolver
import org.xbill.DNS.Type
import org.xbill.DNS.WireParseException

/**
 * Resolves a set of ENR nodes from a host name.
 *
 * @param dnsServer the DNS server to use for DNS query. If null, the default DNS server will be used.
 * @param signingKey the public key associated with the domain, to check that the root DNS record is valid.
 *
 */
class DNSResolver(private val dnsServer: String? = null, private val signingKey: SECP256K1.PublicKey? = null) {

  companion object {
    val logger = LoggerFactory.getLogger(DNSResolver::class.java)
  }

  /**
   * Resolves one DNS record associated with the given domain name.
   *
   * @param domainName the domain name to query
   * @return the DNS entry read from the domain
   */
  public fun resolveRecord(domainName: String): DNSEntry? {
    return resolveRecordRaw(domainName)?.let { DNSEntry.readDNSEntry(it) }
  }

  private fun checkSignature(entry: ENRTreeRoot, sig: SECP256K1.Signature): Boolean {
    if (signingKey == null) {
      return true
    }
    TODO("not implemented, $entry $sig")
    // val recovered = SECP256K1.PublicKey.recoverFromSignature(entry.signedContent(), sig)
    // return signingKey.equals(recovered)
  }

  /**
   * Convenience method to read all ENRs, from a top-level record.
   *
   * @param domainName the DNS domain name to start with
   * @return all ENRs collected
   */
  public fun collectAll(domainName: String): List<EthereumNodeRecord> {
    val nodes = mutableListOf<EthereumNodeRecord>()
    val visitor = object : DNSVisitor {
      override fun visit(enr: EthereumNodeRecord): Boolean {
        nodes.add(enr)
        return true
      }
    }
    visitTree(domainName, visitor)
    return nodes
  }

  /**
   * Reads a complete tree of record, starting with the top-level record.
   * @param domainName the DNS domain name to start with
   * @param visitor the visitor that will look at each record
   */
  public fun visitTree(domainName: String, visitor: DNSVisitor) {
    val entry = resolveRecord(domainName)
    if (entry !is ENRTreeRoot) {
      logger.debug("Root domain name $domainName is not an ENR tree root")
      return
    }
    val linkedStr = resolveRecordRaw("${entry.encodedHash}.$domainName")
    if (linkedStr == null) {
      logger.debug("No linked record under ${entry.encodedHash}.$domainName")
      return
    }
    if (!checkSignature(entry, entry.sig)) {
      logger.debug("ENR tree root $domainName failed signature check")
      return
    }

    val linkedEntry = DNSEntry.readDNSEntry(linkedStr)
    // TODO check hash matches
    if (linkedEntry is ENRNode) {
      visitor.visit(linkedEntry.nodeRecord)
      return
    } else if (linkedEntry is ENRTree) {
      for (e in linkedEntry.entries) {
        if (!internalVisit(e, domainName, visitor)) {
          break
        }
      }
    } else {
      logger.debug("No linked record")
    }
  }

  /**
   * Resolves the first TXT record associated with a domain name,
   * and returns it, or null if no such record exists or the record cannot be read.
   *
   * @param domainName the name of the DNS domain to query
   * @return the first TXT entry of the DNS record
   */
  fun resolveRecordRaw(domainName: String): String? {
    try {
      val resolver = SimpleResolver(dnsServer)
      // required as TXT records are quite big, and dnsjava maxes out UDP payload.
      resolver.setTCP(true)
      val type = Type.TXT
      val name = Name.fromString(domainName, Name.root)
      val rec = Record.newRecord(name, type, DClass.IN)
      val query = Message.newQuery(rec)
      val response = resolver.send(query)
      val records = response.getSectionArray(Section.ANSWER)

      if (records.isNotEmpty()) {
        return records[0].rdataToString()
      } else {
        logger.debug("No TXT record for $domainName")
        return null
      }
    } catch (e: WireParseException) {
      logger.error("Error reading TXT record", e)
      return null
    }
  }

  private fun internalVisit(entryName: String, domainName: String, visitor: DNSVisitor): Boolean {
    try {
      val entry = resolveRecord("$entryName.$domainName")
      if (entry == null) {
        return true
      }

    if (entry is ENRNode) {
      return visitor.visit(entry.nodeRecord)
    } else if (entry is ENRTree) {
      for (e in entry.entries) {
        val keepGoing = internalVisit(e, domainName, visitor)
        if (!keepGoing) {
          return false
        }
      }
    } else if (entry is ENRTreeLink) {
      visitTree(entry.domainName, visitor)
    } else {
      logger.debug("Unsupported type of node $entry")
    }
    return true
    } catch (e: InvalidEntryException) {
      logger.warn(e.message, e)
      return true
    } catch (e: IllegalArgumentException) {
      logger.warn(e.message, e)
      return true
    }
  }
}
