/*
 * Copyright (C) 2015 Stratio (http://stratio.com)
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package com.stratio.crossdata.sql.querybuilder

case class CompletedSelect(initialSelect: InitialSelect,
                      projectedSelect: ProjectedSelect,
                      relatedSelect: RelatedSelect,
                      filteredSelect: Option[FilteredSelect] = None,
                      groupedSelect: Option[GroupedSelect] = None,
                      havingSelect: Option[HavingSelect] = None,
                      orderedSelect: Option[OrderedSelect] = None,
                      limitedSelect: Option[LimitedSelect] = None){

  private[this] def buildSelect(): String = {
    s"""${initialSelect}
       | ${projectedSelect}
       | ${relatedSelect}
       | ${filteredSelect.getOrElse("")}
       | ${groupedSelect.getOrElse("")}
       | ${havingSelect.getOrElse("")}
       | ${orderedSelect.getOrElse("")}
       | ${limitedSelect.getOrElse("")}"""
      .stripMargin
      .replace(System.lineSeparator(), " ")
      .replaceAll ("""\s\s+""", " ").trim
    }

  override def toString: String = {
    if(initialSelect.bracketed)
      s"(${buildSelect()})"
    else
      s"${buildSelect()}"
  }
}
