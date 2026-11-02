# Initial Setup

## Basics

The Maven group-id is defaulted to **!#project-symbolic-name#!** in *cnf/build.bnd* using the bnd **-groupid** instruction.

## Sonarcloud

  - Add Project
  - do the [Sonar Configuration](https://sonarcloud.io/organizations/eclipse.fennec/projects)
  - press `+`_(upper right)_
  - Analyze new project
  - check `!#project-symbolic-name#!` and `SetUp`
  - Select `with a GitHub Action`
  - Copy the `SONAR_TOKEN` to notepad
  - NOT!!!! follow the description .about `github/workflows/build.yml`. _this still [exists](../blob/main/.github/workflows/sonar.yml) in this template._
  - GitHub -> Project-Settings -> Secrets -> [New Repository Secrets](Link: https://github.com/!#orga-symbolic-name#!/!#project-symbolic-name#!/settings/secrets/actions/new)
    - add `SONAR_TOKEN`

## Gradle init

On Linux, grant *gradlew* executable permissions:

```
chmod +x gradlew
./gradlew --version
```

## Git init and push

Execute in your local folder:

```
git init
git add .
git commit -m "initial commit"
git branch -M main
git remote add origin git@github.com:!#orga-symbolic-name#!/!#project-symbolic-name#!.git
git push -u origin main
git checkout -b snapshot
git push --set-upstream origin snapshot
git checkout -b "initial"
```

## Jenkins
- wait up to 15 minutes for the auto-import
- [Jenkins Mainpage](https://ci.eclipse.org/!#orga-symbolic-name#!/)
- [Jenkins !#project-symbolic-name#! branches](https://ci.eclipse.org/!#orga-symbolic-name#!/job/!#project-symbolic-name#!/)

## License check

To execute the license check in you local build folder just run:

`docker run -it --rm -v $(pwd):/github/workspace ghcr.io/apache/skywalking-eyes/license-eye header check`

It will give you the check result

The license check also runs as GitHub action for branches **main** and **snapshot**. 

To exclude or include files, use the `.licenserc.yaml` file in the build folder. To customize the build action use the *.github/workflows/license.yml*

