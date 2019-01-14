#  Licensed to the Apache Software Foundation (ASF) under one
#  or more contributor license agreements.  See the NOTICE file
#  distributed with this work for additional information
#  regarding copyright ownership.  The ASF licenses this file
#  to you under the Apache License, Version 2.0 (the
#  "License"); you may not use this file except in compliance
#  with the License.  You may obtain a copy of the License at
#    http://www.apache.org/licenses/LICENSE-2.0
#  Unless required by applicable law or agreed to in writing,
#  software distributed under the License is distributed on an
#  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
#  KIND, either express or implied.  See the License for the
#  specific language governing permissions and limitations
#  under the License.

FROM centos 
MAINTAINER Apache Tika Team

RUN yum -y update; yum clean all
RUN yum -y install wget curl
RUN wget http://dl.fedoraproject.org/pub/epel/epel-release-latest-7.noarch.rpm
RUN rpm --import http://repos.azulsystems.com/RPM-GPG-KEY-azulsystems
RUN curl -o /etc/yum.repos.d/zulu.repo http://repos.azulsystems.com/rhel/zulu.repo
RUN rpm -ivh epel-release-latest-7.noarch.rpm
RUN yum -y update; yum clean all; yum -y install zulu-8 tesseract systemd; (cd /lib/systemd/system/sysinit.target.wants/; for i in *; do [ $i == systemd-tmpfiles-setup.service ] || rm -f $i; done); rm -f /lib/systemd/system/multi-user.target.wants/*; rm -f /etc/systemd/system/*.wants/*; rm -f /lib/systemd/system/local-fs.target.wants/*; rm -f /lib/systemd/system/sockets.target.wants/*udev*; rm -f /lib/systemd/system/sockets.target.wants/*initctl*; rm -f /lib/systemd/system/basic.target.wants/*; rm -f /lib/systemd/system/anaconda.target.wants/*;
ARG JAR_FILE
RUN useradd -ms /bin/bash tika 
ADD target/tika-server-2.0.0-SNAPSHOT.jar /tika-server.jar
ADD extra/firejail-0.9.56-1.x86_64.rpm /firejail-0.9.56-1.x86_64.rpm
RUN rpm -ivh firejail-0.9.56-1.x86_64.rpm 
ADD extra/jailed-tika /etc/systemd/system/jailed_tika.service
VOLUME ["/sys/fs/cgroup"]
EXPOSE 9998
RUN systemctl enable jailed_tika
CMD ["/usr/sbin/init"]
