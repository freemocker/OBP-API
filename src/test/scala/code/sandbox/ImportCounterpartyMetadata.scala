package code.sandbox

/**
Open Bank Project

Copyright 2011,2015 TESOBE / Music Pictures Ltd.

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

/*
* To use this one-time script, put e.g.
* target_api_hostname=https://localhost:8080
* obp_consumer_key=xxx
* obp_secret_key=yyy
*
* into your props file.
* */

import scala.collection.mutable.ListBuffer
import scala.io.Source
import dispatch._
import net.liftweb.json._
import net.liftweb.json.JsonDSL._
import net.liftweb.common.Full
import code.api.test.SendServerRequests
import code.api.ObpJson._
import code.api.util.APIUtil._
import code.api._
import code.api.ObpJson.BarebonesAccountsJson

case class CounterpartyJSONRecord(name: String, category: String, superCategory: String, logoUrl: String, homePageUrl: String, region: String)
case class UserJSONRecord(email: String, password: String, display_name: String)


// Import counterparty metadata
// Instructions for using this:
// Run a copy of the API somewhere (else)
// Set the paths for users and counterparties. (remove the outer [] from the json)

// TODO Extract this into a separate application.

object ImportCounterpartyMetadata extends SendServerRequests {
  def main(args : Array[String]) {
    implicit val formats = DefaultFormats

    //load json for counterpaties
    var path = "/Users/simonredfern/Documents/OpenBankProject/DATA/ENBD/load_005/OBP_sandbox_counterparties_pretty.json"

    var records = JsonParser.parse(Source.fromFile(path) mkString)
    var counterparties = ListBuffer[CounterpartyJSONRecord]()

    //collect counterparties records
    for(r <- records.children){
      //logger.info(s" extract counterparty records")
      val rec = r.extract[CounterpartyJSONRecord]
      //println (rec.name + "in region " + rec.region)
      counterparties.append(rec)
    }

    println("Got " + counterparties.length + " counterparty records")

    //load sandbox users from json

    path = "/Users/simonredfern/Documents/OpenBankProject/DATA/ENBD/load_005/OBP_sandbox_pretty.json"

    records = JsonParser.parse(Source.fromFile(path) mkString)
    val users = (records \ "users").children
    println("got " + users.length + " users")

    //loop over users from json
    for (u <- users) {
      val user = u.extract[UserJSONRecord]
      println(" ")
      print("login as user: ")
      println (user.email + " - " + user.password)

      if(!OAuthClient.loggedIn) {
        OAuthClient.authenticateWithOBPCredentials(user.email, user.password)
        println(" - ok.")
      }

      print("get users private accounts")
      val accountsJson = ObpGet("/v1.2.1/accounts/private").flatMap(_.extractOpt[BarebonesAccountsJson])
      val accounts : List[BarebonesAccountJson] = accountsJson match {
        case Full(as) => as.accounts.get
        case _ => List[BarebonesAccountJson]()
      }
      println(" - ok.")

      println("get other accounts for the accounts")
      for(a : BarebonesAccountJson <- accounts) {
        print("account: " + a.label.get + " ")
        println(a.bank_id.get)

        val headers : List[Header] = List(Header("obp_limit", "9999999"))  //prevent pagination
        val otherAccountsJson =
          ObpGet("/v1.2.1/banks/"+a.bank_id.get+"/accounts/"+a.id.get+"/owner/other_accounts", headers).flatMap(_.extractOpt[OtherAccountsJson])

        val otherAccounts : List[OtherAccountJson] = otherAccountsJson match {
          case Full(oa) => oa.other_accounts.get
          case _ => List[OtherAccountJson]()
        }


        // In the counterparty json, counterparties have a region (aka bank)
        // However in sandboxes, the bank_id might also contain a suffix (version of the load).
        // By convention use bank_id like region~version so we can split on ~ to get the region

        val bankId = a.bank_id.get

        println(s"bankId is ${bankId}")

        // Convention: lets say that in a bank_id the part before -- is the region and after the -- is just a version
        // e.g. given enbd-uae--g we would want to extract enbd-uae as the region
        // Note we don' use ~ because it messes with OAuth signatures
        val bits = bankId.split("--")
        val region = bits(0)

        println(s"region is ${region}")


        println("get matching json counterparty data for each transaction's other_account")

        for(oa : OtherAccountJson <- otherAccounts) {
          val name = oa.holder.get.name.get.trim


          println(s"Filtering counterparties by region ${region} and counterparty name ${name}")

          val records = counterparties.filter(x => ((x.name equalsIgnoreCase(name)) && (x.region equals region)))
          var found = false

          println(s"Found ${records.size} records")

          //loop over all counterparties (from json) and match to other_account (counterparties), update data
          for (cp: CounterpartyJSONRecord <- records) {
            println(s"cp is Region ${cp.region} Name ${cp.name} Home Page ${cp.homePageUrl}")
            val logoUrl = if(cp.logoUrl.contains("http://www.brandprofiles.com")) cp.homePageUrl else cp.logoUrl
            if (logoUrl.startsWith("http") && oa.metadata.get.image_URL.isEmpty) {
              val json = ("image_URL" -> logoUrl)
              ObpPost("/v1.2.1/banks/" + a.bank_id.get + "/accounts/" + a.id.get + "/owner/other_accounts/" + oa.id.get + "/metadata/image_url", json)
              println("saved " + logoUrl + " as imageURL for counterparty "+ oa.id.get)
              found = true
            } else {
              println("did NOT save " + logoUrl + " as imageURL for counterparty "+ oa.id.get)
          }

            if(cp.homePageUrl.startsWith("http") && !cp.homePageUrl.endsWith("jpg") && !cp.homePageUrl.endsWith("png") && oa.metadata.get.URL.isEmpty) {
              val json = ("URL" -> cp.homePageUrl)
              ObpPost("/v1.2.1/banks/" + a.bank_id.get + "/accounts/" + a.id.get + "/owner/other_accounts/" + oa.id.get + "/metadata/url", json)
              println("saved " + cp.homePageUrl + " as URL for counterparty "+ oa.id.get)
            } else {
              println("did NOT save " + cp.homePageUrl + " as URL for counterparty "+ oa.id.get)
            }

            if(!cp.category.isEmpty && oa.metadata.get.more_info.isEmpty) {
              val moreInfo = (cp.category ) // ("Category: " + cp.category )
              val json = ("more_info" -> moreInfo)
              val result = ObpPost("/v1.2.1/banks/" + a.bank_id.get + "/accounts/" + a.id.get + "/owner/other_accounts/" + oa.id.get + "/metadata/more_info", json)
              if(!result.isEmpty)
                println("saved " + moreInfo + " as more_info for counterparty "+ oa.id.get)
            } else {
              println("did NOT save more_info for counterparty "+ oa.id.get)
            }
          }

        }

      /*println("get transactions for the accounts")
      for(a : BarebonesAccountJson <- accounts) {
        print("account: " + a.label.get + " ")
        println(a.bank_id.get)

        val headers : List[Header] = List(Header("obp_limit", "9999999"))
        val transactionsJson =
          ObpGet("/v1.2.1/banks/"+a.bank_id.get+"/accounts/"+a.id.get+"/owner/transactions", headers).flatMap(_.extractOpt[TransactionsJson])

        val transactions : List[TransactionJson] = transactionsJson match {
          case Full(ts) => ts.transactions.get
          case _ => List[TransactionJson]()
        }

        //uh, matching very specific to rbs data
        val bits = a.bank_id.get.split("-")
        val region = bits(bits.length - 2)

        println("get matching json counterparty data for each transaction's other_account")

        for(t : TransactionJson <- transactions) {
          val name = t.other_account.get.holder.get.name
          val records = counterparties.filter(x => ((x.name equalsIgnoreCase(name.get)) && (x.region equals region)))
          var found = false
          for (cp: CounterpartyJSONRecord <- records) {
            val logoUrl = if(cp.logoUrl.contains("http://www.brandprofiles.com")) cp.homePageUrl else cp.logoUrl
            if (logoUrl.startsWith("http") && t.metadata.get.images.get.isEmpty) {
              val json = ("label" -> "Logo") ~ ("URL" -> logoUrl)
              ObpPost("/v1.2.1/banks/" + a.bank_id.get + "/accounts/" + a.id.get + "/owner/transactions/" + t.id.get + "/metadata/images", json)
              println("saved " + logoUrl + " for transaction "+ t.id.get)
              found = true
            }
          }
        }*/
      }


      OAuthClient.logoutAll()
    }

    sys.exit(0)
  }
}
