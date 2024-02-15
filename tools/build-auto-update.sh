#!/usr/bin/env bash

set -e

# Check we are within a git repo
git_dir=$( git rev-parse --show-toplevel )
if [ -z "${git_dir}" ]; then
	echo "Cannot determine top-level directory for git repo"
	exit 1
fi

# Change to git top-level
cd ${git_dir}

# Check we are in 'master' branch
branch_name=$( git symbolic-ref -q HEAD || echo )
branch_name=${branch_name##refs/heads/}
echo "Current git branch: ${branch_name}"
if [ "${branch_name}" != "master" ]; then
	echo "Unexpected current branch '${branch_name}' - expecting 'master'"
	echo "CTRL-C within 5 seconds to abort"
	sleep 5
fi

# Extract short-form commit hash
short_hash=$( git rev-parse --short HEAD )
if [ -z "${short_hash}" ]; then
	echo "Unable to extract short-form current commit hash"
	exit 1
fi
echo "HEAD commit is: ${short_hash}"

# Check there are no uncommitted changes
uncommitted=$( git status --short --untracked-files=no )
if [ ! -z "${uncommitted}" ]; then
	echo "Cannot continue due to uncommitted files:"
	echo "${uncommitted}"
	exit 1
fi

# Determine project name
project=$( perl -n -e 'if (m/<artifactId>(\w+)<.artifactId>/) { print $1; exit }' pom.xml $)
if [ -z "${project}" ]; then
	echo "Unable to determine project name from pom.xml?"
	exit 1
fi

# Actually rebuild JAR
echo "Building ${project} JAR..."
mvn clean package 1>/tmp/${project}-mvn-build.log 2>&1
if [ "$?" != "0" -o ! -r target/${project}*.jar ]; then
	echo "Maven build failed. For details, see /tmp/${project}-mvn-build.log"
	exit 1
fi

# Convert packaged JAR to XORed update form
echo "Building ${project}.update..."
java -cp target/${project}*.jar org.qortal.XorUpdate target/${project}*.jar ${project}.update
if [ "$?" != "0" ]; then
	echo "Failed to create XORed auto-update JAR"
	exit 1
fi

# Create auto-update branch from this commit
update_branch=auto-update-${short_hash}

if git show-ref --quiet --verify refs/heads/${update_branch}; then
	echo "Existing auto-update branch based on this commit (${short_hash}) - deleting..."
	git branch --delete --force ${update_branch}
fi

echo "Checking out new auto-update branch based on this commit (${short_hash})..."
git checkout --orphan ${update_branch}
git rm --cached -fr . 1>/dev/null

git add ${project}.update

git commit --message "XORed, auto-update JAR based on commit ${short_hash}"
git push --set-upstream origin --force-with-lease ${update_branch}

branch_name=${branch_name-master}

echo "Changing back to '${branch_name}' branch"
git checkout --force ${branch_name}
