<%--
 Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
--%>
<%@ taglib uri = "http://java.sun.com/jsp/jstl/core" prefix = "c" %>
<c:set var="testVar" value="${'testValue'}" scope="page"/>
<c:set var="testVar" value="${'testValue'}" scope="request"/>
<c:set var="testVar" value="${'testValue'}" scope="session"/>
<c:set var="testVar" value="${'testValue'}" scope="application"/>
<c:remove var="testVar" scope="page"/>
pageContext value=<%= pageContext.getAttribute("testVar") %>
request value=<%= request.getAttribute("testVar") %>
session value=<%= session.getAttribute("testVar") %>
application value=<%= application.getAttribute("testVar") %>