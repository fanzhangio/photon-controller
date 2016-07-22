#!/bin/bash
set -x -e

# Use branch name as version of RPM. We cannot have '-' in the RPM version so that
# we cannot have SNAPSHOT at the end as we have with tar file.
# Resulting RPM will be named as 'photon-controller-core-develop.rpm' for develop branch
# and 'photon-controller-core-v0.9.1.rpm' for v0.9.1 branch.
BRANCH=${GERRIT_BRANCH:-`git rev-parse --abbrev-ref HEAD`}
COMMIT=`git rev-parse --short HEAD`
VERSION=${BRANCH}

# An increasing number is needed for release number part of RPM. This is used for updating
# from old RPM to new RPM within same version by tdnf/yum. To autmoate incrementing the
# release number we will use total commit count in current branch.
# RPMs have following version format.
# <version>-<release>
# And we replace <version> with [branch_name], and <release> with [commit_count.commit_hash].
COMMIT_COUNT=`git rev-list HEAD | wc -l | xargs`

ROOT=`git rev-parse --show-toplevel`
SOURCES_DIR="${ROOT}/artifacts/rpms/SOURCES"
SPECS_DIR="${ROOT}/artifacts/rpms/SPECS"
RPMS_DIR="${ROOT}/artifacts/build/RPMS"
TEMP_DIR=$(mktemp -d "${ROOT}/create_tar.XXXXX")
TAR_PATH="/java/photon-controller-core/build/distributions/"
TAR_PREFIX="photon-controller-core"
RPM_PREFIX="photon-controller"
ENVOY_VIB_URL="http://artifactory.ec.eng.vmware.com/artifactory/esxcloud-archives/envoy/${BRANCH}/latest/vmware-envoy-latest.vib"

trap "rm -rf ${TEMP_DIR}; rm -rf ${SOURCES_DIR};" EXIT

# Create source tar file for use by RPM spec file
mkdir -p "${SOURCES_DIR}"

cd "${TEMP_DIR}"
FILENAME=`find "${ROOT}${TAR_PATH}" -iname "${TAR_PREFIX}*.tar"`
tar -xf "${FILENAME}"

VERSION=`echo $VERSION | tr "-" "_"`
RPM_DIR="${RPM_PREFIX}-${VERSION}"
mv ${TAR_PREFIX}* "${RPM_DIR}"
tar -czf "${SOURCES_DIR}/${RPM_DIR}.tar" "${RPM_DIR}"

cp "${SPECS_DIR}"/* "${SOURCES_DIR}"

# Download pre-build ENVOY vib from artifactory. Ignore if not found, in the case when branches do not match in the URL.
wget "${ENVOY_VIB_URL}" -P "${SOURCES_DIR}" || true

# Build the Photon-Controller vib from local repo and copy to SROUCES folder
cd "${ROOT}"

docker pull vmware/photon-controller-rpm-builder

docker run -i --rm \
  -v `pwd`:`pwd` \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -w `pwd`/python \
  vmware/photon-controller-rpm-builder \
  make clean vib-only

cp "${ROOT}"/python/dist/* "${SOURCES_DIR}"

DEBUG_OPTIONS=""
if [ "$DEBUG" == "true" ]; then
  DEBUG_OPTIONS="-t"
fi

# Build the RPM package inside Photon OS (container) so that right dependencies
# are generated by the package builder for PhotonOS linux distribution.
docker pull vmware/photon-controller-rpm-builder
docker run -i ${DEBUG_OPTIONS} \
  -v "${ROOT}":/photon-controller \
  -v "${SOURCES_DIR}":/usr/src/photon/SOURCES \
  -v "${RPMS_DIR}":/usr/src/photon/RPMS \
  -w /photon-controller/artifacts \
  vmware/photon-controller-rpm-builder \
  ./create-rpm-helper.sh "${VERSION}" "${COMMIT}" "${COMMIT_COUNT}" "${DEBUG}"

# Verify rpm was created and could be installed.
docker run -i \
  -v "${ROOT}"/artifacts/build/RPMS/x86_64:/rpms \
  vmware/photon-controller-rpm-builder \
  bash -c 'ls /rpms/photon-controller*.rpm | xargs rpm -Uvh && [ -d /usr/lib/esxcloud/photon-controller-core ]'
