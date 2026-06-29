#!/bin/bash

#
#  Alfresco Resilient Node Processor - Do things with nodes
#  Copyright (C) 2023-2026 Saidone
#
#  This program is free software: you can redistribute it and/or modify
#  it under the terms of the GNU General Public License as published by
#  the Free Software Foundation, either version 3 of the License, or
#  (at your option) any later version.
#
#  This program is distributed in the hope that it will be useful,
#  but WITHOUT ANY WARRANTY; without even the implied warranty of
#  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
#  GNU General Public License for more details.
#
#  You should have received a copy of the GNU General Public License
#  along with this program.  If not, see <http://www.gnu.org/licenses/>.
#

DIST_DIR=ranp

if [ -e $DIST_DIR ]; then rm -rf $DIST_DIR; fi
mkdir -p $DIST_DIR/log
mkdir -p $DIST_DIR/config
mvn package -DskipTests -Dlicense.skip=true
cp target/ranp*.jar $DIST_DIR
cp src/main/resources/application.yml $DIST_DIR/config
cp src/main/resources/example*.json $DIST_DIR